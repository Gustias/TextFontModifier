package me.itstautvydas.textfontmodifier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
public class TextProcessor {

    private final TextFontModifierPlugin plugin;
    private Pattern regexPattern;
    private final Map<String, Font> fonts = new HashMap<>();

    /**
     * Placeholder format you can output from Skript/bossbar text:
     *   <noxhead:UUID>
     * Example:
     *   <noxhead:3e7a89ee-c4e2-4392-a317-444b861b0794>
     */
    private static final Pattern NOX_HEAD_PLACEHOLDER =
            Pattern.compile("<noxhead:([0-9a-fA-F\\-]{36})(?::([^>]+))?>");

    private static Map<String, String> parseNoxParams(@Nullable String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) return out;

        // params separated by :
        // example: "y=-2:x=1:scale=0.9:hat=true:slim=false"
        String[] parts = raw.split(":");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) continue;
            String k = part.substring(0, eq).trim().toLowerCase();
            String v = part.substring(eq + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return out;
    }

    public TextProcessor(TextFontModifierPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var regexString = getRegex();
        regexPattern = (regexString == null || regexString.isEmpty()) ? null : Pattern.compile(regexString);

        var fonts = plugin.getConfig().getConfigurationSection("fonts");
        this.fonts.clear();

        if (fonts == null) {
            plugin.getLogger().warning("Missing 'fonts' configuration section! No fonts were registered...");
            return;
        }

        // Cache the fonts
        for (var fontName : fonts.getKeys(false)) {
            var section = fonts.getConfigurationSection(fontName);
            if (section == null) continue;
            this.fonts.put(fontName, new Font(section.getString("name"), section.getString("special-symbol")));
        }
        plugin.getLogger().info("Registered fonts: " + String.join(", ", this.fonts.keySet()));
    }

    public String getRegex() {
        return plugin.getConfig().getString("regex.value", "");
    }

    public boolean isRegexInverted() {
        return plugin.getConfig().getBoolean("regex.invert");
    }

    // 1.21 support, simple strings now are actually just strings, not objects
    private JsonObject forceToObject(JsonElement element) {
        if (element == null)
            return null;

        if (!element.isJsonPrimitive()) {
            if (!element.isJsonObject())
                return null;
            return element.getAsJsonObject();
        }
        var newObj = new JsonObject();
        newObj.addProperty("text", element.getAsString());
        return newObj;
    }

    public void processExtra(@Nullable Font font, JsonArray array) {
        if (array == null) return;

        for (int i = 0; i < array.size(); i++) {
            var obj = forceToObject(array.get(i));
            if (obj == null)
                continue;

            array.set(i, obj);

            var previous = i > 0 ? forceToObject(array.get(i - 1)) : null;
            processComponent(font, previous, obj);
        }
    }

    private Font findFontSymbol(String text) {
        if (text == null) return null;
        for (var font : fonts.values()) {
            if (font == null) continue;
            var symbol = font.specialSymbol();
            if (symbol != null && !symbol.isEmpty() && text.contains(symbol))
                return font;
        }
        return null;
    }

    /**
     * Expand any occurrences of <noxhead:UUID> inside a TEXT component into real components:
     *   - keeps text surrounding the placeholder
     *   - injects {"translate": "%%nox_uuid%%<uuid>,false,0,0,1.0"} nodes into extra
     */
    private void expandNoxHeadPlaceholders(JsonObject obj) {

        if (obj == null) return;
        if (!obj.has("text") || !obj.get("text").isJsonPrimitive())
            return;

        String text = obj.get("text").getAsString();
        Matcher m = NOX_HEAD_PLACEHOLDER.matcher(text);
        if (!m.find())
            return;

        JsonArray oldExtra = null;
        if (obj.has("extra") && obj.get("extra").isJsonArray()) {
            oldExtra = obj.getAsJsonArray("extra");
        }

        JsonArray newExtra = new JsonArray();

        int last = 0;
        boolean firstMatch = true;

        m.reset();
        while (m.find()) {
            int start = m.start();
            int end = m.end();

            // Text before this placeholder
            String before = text.substring(last, start);

            if (firstMatch) {
                // Base object's text becomes everything before the first placeholder
                obj.addProperty("text", before);
                firstMatch = false;
            } else {
                if (!before.isEmpty()) {
                    JsonObject mid = new JsonObject();
                    mid.addProperty("text", before);
                    newExtra.add(mid);
                }
            }

            // Inject Noxesium head translate component
            String uuid = m.group(1);
            Map<String, String> params = parseNoxParams(m.group(2));

            boolean slim = Boolean.parseBoolean(params.getOrDefault("slim", "false"));
            int x = Integer.parseInt(params.getOrDefault("x", "0"));
            int y = Integer.parseInt(params.getOrDefault("y", "0"));
            double scale = Double.parseDouble(params.getOrDefault("scale", "1.0"));
            boolean hat = Boolean.parseBoolean(params.getOrDefault("hat", "true"));

            JsonObject head = new JsonObject();
            head.addProperty("translate",
                    "%nox_uuid%" + uuid + "," + slim + "," + x + "," + y + "," + scale + "," + hat);
            head.addProperty("fallback", "");
            newExtra.add(head);
            last = end;
        }

        // Trailing text after the last placeholder
        String tail = text.substring(last);
        if (!tail.isEmpty()) {
            JsonObject tailObj = new JsonObject();
            tailObj.addProperty("text", tail);
            newExtra.add(tailObj);
        }

        // Append any original extras at the end
        if (oldExtra != null) {
            for (int i = 0; i < oldExtra.size(); i++) {
                newExtra.add(oldExtra.get(i));
            }
        }
        obj.add("extra", newExtra);
    }

    /**
     * Processes a single component object, including:
     * - expands <noxhead:UUID> placeholders in "text"
     * - text components ("text")
     * - translatable components ("translate") + their "with" args
     * - nested "extra"
     */
    public void processComponent(@Nullable Font font, JsonObject previous, JsonObject obj) {
        if (obj == null)
            return;

        // Expand placeholders into real Noxesium translate components
        expandNoxHeadPlaceholders(obj);

        // Recurse into nested structures
        if (obj.has("with") && obj.get("with").isJsonArray())
            processExtra(font, obj.getAsJsonArray("with"));

        if (obj.has("extra") && obj.get("extra").isJsonArray())
            processExtra(font, obj.getAsJsonArray("extra"));

        // Apply font to this node (text or translatable)
        applyFontToNode(font, previous, obj);
    }

    private void applyFontToNode(@Nullable Font font, JsonObject previous, JsonObject obj) {
        // If another system already set a font (some packs/components), keep it.
        if (obj.has("font"))
            return;

        // Determine which string field we should inspect for regex + symbol detection.
        // This supports:
        // - "text" (TextComponent)
        // - "translate" (TranslatableComponent)
        String field;

        if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
            field = "text";
        } else if (obj.has("translate") && obj.get("translate").isJsonPrimitive()) {
            field = "translate";
        } else {
            return; // nothing to do
        }

        String str = obj.get(field).getAsString();

        // Noxesium heads
        if ("translate".equals(field)) {
            String key = str.toLowerCase();
            if (key.contains("nox_uuid") || key.startsWith("nox_"))
                return;
        }

        // Regex gate
        if (regexPattern != null) {
            boolean matches = regexPattern.matcher(str.toLowerCase()).matches();
            if (isRegexInverted() == matches) {
                // Inverted logic: if invert==matches, we should NOT apply
                return;
            }
        }

        // Preserve existing clickEvent merge behavior
        if (obj.has("clickEvent") && previous != null && previous.has("text") && previous.get("text").isJsonPrimitive()) {
            String previousText = previous.get("text").getAsString();
            obj.remove("clickEvent");
            previous.addProperty("text", "");
            if ("text".equals(field)) {
                str = previousText + str;
                obj.addProperty("text", str);
            }
        }

        // If no forced font (font == null), try to detect via special symbol in this node's string
        if (font == null) {
            var detected = findFontSymbol(str);
            if (detected != null) {
                // Strip symbol only from "text" nodes
                if ("text".equals(field)) {
                    var symbol = detected.specialSymbol();
                    if (symbol != null && !symbol.isEmpty())
                        obj.addProperty("text", str.replace(symbol, ""));
                }
                obj.addProperty("font", detected.font());
            }
            return;
        }

        // Forced font: apply it (but never on Noxesium keys because it's returned above)
        obj.addProperty("font", font.font());
    }

    public JsonElement modifyFontJson(String packetName, JsonElement json) {
        if (json == null)
            return null;

        var obj = forceToObject(json);
        if (obj == null)
            return json;

        var section = plugin.getConfig().getConfigurationSection("packets." + packetName);
        if (section == null)
            return obj;

        var fontName = section.getString("forced-font", "");
        var font = fontName == null || fontName.isEmpty() ? null : fonts.get(fontName);

        // Process whole component tree
        processComponent(font, null, obj);
        return obj;
    }
}