package com.hoshitsuki.paypayledger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CategoryRuleStore {
    private static final String PREFS = "category_rules";
    private static final String KEY_MAPPINGS = "category_mapping_json";
    private static final String KEY_OVERRIDES = "merchant_override_json";
    private static final String KEY_IMPORTED_GROUPS = "imported_rule_groups_json";

    private final SharedPreferences prefs;
    private final LinkedHashMap<String, RuleGroup> builtInGroups = new LinkedHashMap<String, RuleGroup>();

    public CategoryRuleStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        buildBuiltInGroups();
    }

    public boolean hasUserMappings() {
        return prefs.contains(KEY_MAPPINGS);
    }

    public List<RuleGroup> getEffectiveRuleGroups() {
        LinkedHashMap<String, RuleGroup> merged = new LinkedHashMap<String, RuleGroup>();
        for (RuleGroup group : builtInGroups.values()) {
            merged.put(group.groupId, group.copy());
        }
        for (RuleGroup imported : loadImportedGroups().values()) {
            RuleGroup target = merged.get(imported.groupId);
            if (target == null) {
                merged.put(imported.groupId, imported.copy());
            } else {
                target.keywords.addAll(imported.keywords);
                target.imported = true;
            }
        }
        ArrayList<RuleGroup> groups = new ArrayList<RuleGroup>(merged.values());
        Collections.sort(groups, new Comparator<RuleGroup>() {
            @Override
            public int compare(RuleGroup a, RuleGroup b) {
                return a.order - b.order;
            }
        });
        return groups;
    }

    public Map<String, CategoryMapping> loadMappings() {
        LinkedHashMap<String, CategoryMapping> mappings = new LinkedHashMap<String, CategoryMapping>();
        String raw = prefs.getString(KEY_MAPPINGS, "");
        if (raw.length() == 0) {
            return mappings;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                CategoryMapping mapping = new CategoryMapping(
                        item.optString("group_id"),
                        item.optString("major"),
                        item.optString("minor"));
                if (mapping.groupId.length() > 0 && mapping.major.length() > 0) {
                    mappings.put(mapping.groupId, mapping);
                }
            }
        } catch (JSONException ignored) {
        }
        return mappings;
    }

    public Map<String, MerchantOverride> loadOverrides() {
        LinkedHashMap<String, MerchantOverride> overrides = new LinkedHashMap<String, MerchantOverride>();
        String raw = prefs.getString(KEY_OVERRIDES, "");
        if (raw.length() == 0) {
            return overrides;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                MerchantOverride override = new MerchantOverride(
                        item.optString("merchant_key"),
                        item.optString("display_name"),
                        item.optString("major"),
                        item.optString("minor"));
                if (override.merchantKey.length() > 0 && override.major.length() > 0) {
                    overrides.put(override.merchantKey, override);
                }
            }
        } catch (JSONException ignored) {
        }
        return overrides;
    }

    public CategoryMapping recommendedMapping(String groupId) {
        if ("convenience_store".equals(groupId)) {
            return new CategoryMapping(groupId, "食品餐饮", "便利店");
        }
        if ("supermarket".equals(groupId)) {
            return new CategoryMapping(groupId, "食品餐饮", "食材采购");
        }
        if ("restaurant_fast".equals(groupId)) {
            return new CategoryMapping(groupId, "食品餐饮", "日常正餐");
        }
        if ("restaurant_dining".equals(groupId)) {
            return new CategoryMapping(groupId, "休闲娱乐", "外出吃饭");
        }
        if ("clothing_store".equals(groupId)) {
            return new CategoryMapping(groupId, "购物消费", "服饰鞋包");
        }
        if ("transport_train".equals(groupId)) {
            return new CategoryMapping(groupId, "出行交通", "公共交通");
        }
        if ("subscription".equals(groupId)) {
            return new CategoryMapping(groupId, "居家生活", "App会员");
        }
        if ("entertainment_movie".equals(groupId)) {
            return new CategoryMapping(groupId, "休闲娱乐", "电影娱乐");
        }
        return new CategoryMapping(groupId, "待分类", "");
    }

    public void saveMappings(List<CategoryMapping> mappings) {
        JSONArray array = new JSONArray();
        for (CategoryMapping mapping : mappings) {
            if (mapping.groupId.length() == 0 || mapping.major.trim().length() == 0) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("group_id", mapping.groupId);
                item.put("major", mapping.major.trim());
                item.put("minor", mapping.minor.trim());
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_MAPPINGS, array.toString()).apply();
    }

    public void saveMerchantOverride(String merchant, String major, String minor) {
        String key = merchantKey(merchant);
        LinkedHashMap<String, MerchantOverride> overrides = new LinkedHashMap<String, MerchantOverride>(loadOverrides());
        overrides.put(key, new MerchantOverride(key, merchant, major.trim(), minor.trim()));
        saveOverrides(new ArrayList<MerchantOverride>(overrides.values()));
    }

    public void deleteMerchantOverride(String merchantKey) {
        LinkedHashMap<String, MerchantOverride> overrides = new LinkedHashMap<String, MerchantOverride>(loadOverrides());
        overrides.remove(merchantKey);
        saveOverrides(new ArrayList<MerchantOverride>(overrides.values()));
    }

    public CategoryResult classify(String merchant) {
        String key = merchantKey(merchant);
        MerchantOverride override = loadOverrides().get(key);
        if (override != null) {
            return new CategoryResult(override.major, override.minor, "", "商户修正");
        }

        RuleGroup group = matchRuleGroup(merchant);
        if (group == null) {
            return new CategoryResult("待分类", "", "", "未匹配");
        }

        CategoryMapping mapping = loadMappings().get(group.groupId);
        if (mapping == null) {
            mapping = recommendedMapping(group.groupId);
            return new CategoryResult(mapping.major, mapping.minor, group.groupId, "推荐映射");
        }
        return new CategoryResult(mapping.major, mapping.minor, group.groupId, "用户映射");
    }

    public RuleGroup matchRuleGroup(String merchant) {
        String normalized = normalizeMerchant(merchant);
        for (RuleGroup group : getEffectiveRuleGroups()) {
            for (String keyword : group.keywords) {
                String key = normalizeMerchant(keyword);
                if (key.length() == 0) {
                    continue;
                }
                if (key.length() <= 2) {
                    if (normalized.equals(key) || normalized.startsWith(key + " ") || normalized.startsWith(key)) {
                        return group;
                    }
                } else if (normalized.contains(key)) {
                    return group;
                }
            }
        }
        return null;
    }

    public String importCategoryMappingsCsv(String csv) throws Exception {
        List<String[]> rows = parseCsv(csv);
        requireHeader(rows, "group_id", "major", "minor");
        Set<String> knownIds = new LinkedHashSet<String>();
        for (RuleGroup group : getEffectiveRuleGroups()) {
            knownIds.add(group.groupId);
        }
        LinkedHashMap<String, CategoryMapping> current = new LinkedHashMap<String, CategoryMapping>(loadMappings());
        int updated = 0;
        int skipped = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String groupId = cell(row, 0).trim();
            String major = cell(row, 1).trim();
            String minor = cell(row, 2).trim();
            if (groupId.length() == 0 || major.length() == 0 || !knownIds.contains(groupId)) {
                skipped++;
                continue;
            }
            current.put(groupId, new CategoryMapping(groupId, major, minor));
            updated++;
        }
        saveMappings(new ArrayList<CategoryMapping>(current.values()));
        return "导入分类映射 " + updated + " 条，跳过 " + skipped + " 条";
    }

    public String importMerchantOverridesCsv(String csv) throws Exception {
        List<String[]> rows = parseCsv(csv);
        requireHeader(rows, "merchant_key", "display_name", "major", "minor");
        LinkedHashMap<String, MerchantOverride> current = new LinkedHashMap<String, MerchantOverride>(loadOverrides());
        int updated = 0;
        int skipped = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String key = cell(row, 0).trim();
            String display = cell(row, 1).trim();
            String major = cell(row, 2).trim();
            String minor = cell(row, 3).trim();
            if (key.length() == 0 && display.length() > 0) {
                key = merchantKey(display);
            }
            if (key.length() == 0 || major.length() == 0) {
                skipped++;
                continue;
            }
            current.put(key, new MerchantOverride(key, display, major, minor));
            updated++;
        }
        saveOverrides(new ArrayList<MerchantOverride>(current.values()));
        return "导入商户修正 " + updated + " 条，跳过 " + skipped + " 条";
    }

    public String importRuleGroupsCsvAppend(String csv) throws Exception {
        List<String[]> rows = parseCsv(csv);
        requireHeader(rows, "group_id", "group_name", "keywords");
        LinkedHashMap<String, RuleGroup> imported = new LinkedHashMap<String, RuleGroup>(loadImportedGroups());
        int updated = 0;
        int risk = 0;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String groupId = cell(row, 0).trim();
            String groupName = cell(row, 1).trim();
            String keywords = cell(row, 2).trim();
            if (groupId.length() == 0 || groupName.length() == 0 || keywords.length() == 0) {
                continue;
            }
            RuleGroup target = imported.get(groupId);
            if (target == null) {
                target = new RuleGroup(groupId, groupName, 1000 + imported.size(), true);
                imported.put(groupId, target);
            }
            for (String keyword : keywords.split("\\|")) {
                String cleaned = keyword.trim();
                if (cleaned.length() > 0) {
                    target.keywords.add(cleaned);
                }
            }
            RuleGroup effective = findEffectiveGroupAfterImport(groupId, target);
            if (effective.keywords.size() < 100) {
                risk++;
            }
            updated++;
        }
        saveImportedGroups(new ArrayList<RuleGroup>(imported.values()));
        return "导入商户合集 " + updated + " 组" + (risk > 0 ? "，其中 " + risk + " 组少于 100 个关键词" : "");
    }

    public String exportCategoryMappingsCsv() {
        StringBuilder out = new StringBuilder("\uFEFFgroup_id,major,minor\n");
        Map<String, CategoryMapping> mappings = loadMappings();
        for (RuleGroup group : getEffectiveRuleGroups()) {
            CategoryMapping mapping = mappings.get(group.groupId);
            if (mapping == null) {
                mapping = recommendedMapping(group.groupId);
            }
            out.append(csv(group.groupId)).append(',')
                    .append(csv(mapping.major)).append(',')
                    .append(csv(mapping.minor)).append('\n');
        }
        return out.toString();
    }

    public String exportMerchantOverridesCsv() {
        StringBuilder out = new StringBuilder("\uFEFFmerchant_key,display_name,major,minor\n");
        for (MerchantOverride override : loadOverrides().values()) {
            out.append(csv(override.merchantKey)).append(',')
                    .append(csv(override.displayName)).append(',')
                    .append(csv(override.major)).append(',')
                    .append(csv(override.minor)).append('\n');
        }
        return out.toString();
    }

    public String exportRuleGroupsCsv() {
        StringBuilder out = new StringBuilder("\uFEFFgroup_id,group_name,keywords\n");
        for (RuleGroup group : getEffectiveRuleGroups()) {
            ArrayList<String> sorted = new ArrayList<String>(group.keywords);
            Collections.sort(sorted);
            out.append(csv(group.groupId)).append(',')
                    .append(csv(group.groupName)).append(',')
                    .append(csv(join(sorted, "|"))).append('\n');
        }
        return out.toString();
    }

    public static String merchantKey(String merchant) {
        return normalizeMerchant(merchant).replaceAll("[^a-z0-9\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    public static String normalizeMerchant(String merchant) {
        String normalized = Normalizer.normalize(merchant == null ? "" : merchant, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[・＊*／/\\\\()（）\\[\\]【】.,，。]", " ")
                .replaceAll("\\bpaypay\\b", " ")
                .replaceAll("\\bvisa\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        normalized = normalized.replaceAll("\\s+[0-9]{3,}$", "");
        return normalized;
    }

    private RuleGroup findEffectiveGroupAfterImport(String groupId, RuleGroup imported) {
        RuleGroup base = builtInGroups.containsKey(groupId) ? builtInGroups.get(groupId).copy() : imported.copy();
        base.keywords.addAll(imported.keywords);
        return base;
    }

    private Map<String, RuleGroup> loadImportedGroups() {
        LinkedHashMap<String, RuleGroup> groups = new LinkedHashMap<String, RuleGroup>();
        String raw = prefs.getString(KEY_IMPORTED_GROUPS, "");
        if (raw.length() == 0) {
            return groups;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                RuleGroup group = new RuleGroup(item.optString("group_id"), item.optString("group_name"), 1000 + i, true);
                JSONArray keywords = item.optJSONArray("keywords");
                if (keywords != null) {
                    for (int j = 0; j < keywords.length(); j++) {
                        String keyword = keywords.optString(j);
                        if (keyword.length() > 0) {
                            group.keywords.add(keyword);
                        }
                    }
                }
                if (group.groupId.length() > 0 && group.groupName.length() > 0) {
                    groups.put(group.groupId, group);
                }
            }
        } catch (JSONException ignored) {
        }
        return groups;
    }

    private void saveImportedGroups(List<RuleGroup> groups) {
        JSONArray array = new JSONArray();
        for (RuleGroup group : groups) {
            JSONObject item = new JSONObject();
            try {
                item.put("group_id", group.groupId);
                item.put("group_name", group.groupName);
                JSONArray keywords = new JSONArray();
                ArrayList<String> sorted = new ArrayList<String>(group.keywords);
                Collections.sort(sorted);
                for (String keyword : sorted) {
                    keywords.put(keyword);
                }
                item.put("keywords", keywords);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_IMPORTED_GROUPS, array.toString()).apply();
    }

    private void saveOverrides(List<MerchantOverride> overrides) {
        JSONArray array = new JSONArray();
        for (MerchantOverride override : overrides) {
            if (override.merchantKey.length() == 0 || override.major.trim().length() == 0) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("merchant_key", override.merchantKey);
                item.put("display_name", override.displayName);
                item.put("major", override.major.trim());
                item.put("minor", override.minor.trim());
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_OVERRIDES, array.toString()).apply();
    }

    private void buildBuiltInGroups() {
        addGroup(0, "convenience_store", "便利店", new String[]{
                "lawson", "ローソン", "ローソンストア", "natural lawson", "ナチュラルローソン", "familymart", "family mart", "ファミリーマート", "ファミマ", "famima",
                "7-eleven", "7 eleven", "seven eleven", "セブンイレブン", "セブン-イレブン", "セブン", "mini stop", "ministop", "ミニストップ", "デイリーヤマザキ",
                "daily yamazaki", "newdays", "ニューデイズ", "ポプラ", "poplar", "seicomart", "セイコーマート", "コミュニティストア", "生活彩家", "スリーエフ"
        });
        addGroup(1, "supermarket", "超市", new String[]{
                "aeon", "イオン", "イオンリテール", "maxvalu", "マックスバリュ", "valor", "バロー", "業務スーパー", "gyomu super", "イトーヨーカドー",
                "york benimaru", "ヨークベニマル", "ライフ", "life supermarket", "seiyu", "西友", "maruetsu", "マルエツ", "summit", "サミット",
                "ok store", "オーケー", "yaoko", "ヤオコー", "apita", "アピタ", "piago", "ピアゴ", "coop", "コープ",
                "生協", "成城石井", "seijo ishii", "mandai", "万代", "kansai super", "関西スーパー", "barrow"
        });
        addGroup(2, "restaurant_fast", "快餐", new String[]{
                "mcdonald", "mcdonalds", "マクドナルド", "マック", "kfc", "ケンタッキー", "kentucky", "sukiya", "すき家", "matsuya",
                "松屋", "yoshinoya", "吉野家", "mos burger", "モスバーガー", "burger king", "バーガーキング", "wendys", "ウェンディーズ", "first kitchen",
                "フレッシュネスバーガー", "freshness burger", "subway", "サブウェイ", "なか卯", "nakau", "coco ichibanya", "ココイチ", "coco壱番屋", "てんや",
                "天丼てんや", "hanamaru", "はなまるうどん", "丸亀製麺", "marugame", "リンガーハット", "ringer hut", "日高屋", "hidakaya"
        });
        addGroup(3, "restaurant_dining", "正餐", new String[]{
                "restaurant", "レストラン", "料理", "食堂", "台湾料理", "吉香楼", "ラーメン", "らーめん", "餃子", "寿司",
                "sushi", "焼肉", "yakiniku", "居酒屋", "izakaya", "カフェ", "cafe", "喫茶", "ガスト", "saizeriya",
                "サイゼリヤ", "ジョナサン", "デニーズ", "ロイヤルホスト", "びっくりドンキー", "大戸屋", "ootoya", "やよい軒", "コメダ", "komeda",
                "スターバックス", "starbucks", "ドトール", "doutor", "タリーズ", "tullys", "牛角", "gyukaku", "鳥貴族", "torikizoku",
                "くら寿司", "スシロー", "はま寿司", "かっぱ寿司", "焼鳥", "中華", "定食", "ビストロ"
        });
        addGroup(4, "clothing_store", "服装店", new String[]{
                "uniqlo", "ユニクロ", "gu", "ジーユー", "zara", "h&m", "hm", "しまむら", "shimamura", "無印良品",
                "muji", "beams", "ビームス", "ships", "シップス", "united arrows", "ユナイテッドアローズ", "global work", "グローバルワーク", "wego",
                "urban research", "アーバンリサーチ", "journal standard", "nano universe", "ナノユニバース", "abc mart", "abc-mart", "エービーシーマート", "nike", "ナイキ",
                "adidas", "アディダス", "puma", "プーマ", "workman", "ワークマン", "right-on", "ライトオン", "aoki", "洋服の青山",
                "青山", "honeys", "ハニーズ", "earth music", "gap", "old navy", "zoff", "jins", "眼鏡市場"
        });
        addGroup(5, "transport_train", "电车地铁", new String[]{
                "jr", "jr東日本", "jr東海", "jr西日本", "jre", "suica", "pasmo", "manaca", "icoca", "toica",
                "kitaca", "sugoca", "nimoca", "はやかけん", "地下鉄", "metro", "東京メトロ", "tokyo metro", "都営地下鉄", "名鉄",
                "meitetsu", "近鉄", "kintetsu", "阪急", "hankyu", "阪神", "hanshin", "京急", "keikyu", "京王",
                "keio", "小田急", "odakyu", "東急", "tokyu", "西武鉄道", "seibu", "東武鉄道", "tobu", "バス",
                "bus", "交通", "鉄道", "駅", "乗車券", "定期券", "空港線", "交通局"
        });
        addGroup(6, "subscription", "订阅服务", new String[]{
                "openai", "chatgpt", "openai chatgpt", "apple", "apple.com/bill", "app store", "icloud", "google", "google play", "youtube",
                "youtube premium", "netflix", "spotify", "amazon prime", "prime video", "kindle", "audible", "disney", "disney+", "hulu",
                "u-next", "unext", "abema", "dazn", "dropbox", "notion", "slack", "github", "microsoft", "office 365",
                "adobe", "creative cloud", "canva", "zoom", "figma", "subscription", "subscr", "サブスク", "会員", "月額",
                "年額", "premium", "pro plan", "cloud", "storage", "会员"
        });
        addGroup(7, "entertainment_movie", "电影娱乐", new String[]{
                "movie", "映画", "映画館", "映画チケット", "cinema", "toho", "toho cinemas", "tohoシネマズ", "イオンシネマ", "aeon cinema",
                "united cinemas", "ユナイテッドシネマ", "109シネマズ", "109 cinemas", "movix", "松竹", "ピカデリー", "シネマサンシャイン", "cinema sunshine", "t joy",
                "t-joy", "ティジョイ", "os cinemas", "osシネマズ", "kino cinema", "キノシネマ", "チネチッタ", "cinetta", "ミッドランドスクエアシネマ", "伏見ミリオン座",
                "センチュリーシネマ", "シネプレックス", "humax", "ヒューマックス", "シネマート", "テアトル", "映画鑑賞", "ムビチケ", "movieticket", "チケット"
        });
    }

    private void addGroup(int order, String groupId, String groupName, String[] bases) {
        RuleGroup group = new RuleGroup(groupId, groupName, order, false);
        group.keywords.addAll(expandKeywords(bases));
        builtInGroups.put(groupId, group);
    }

    private Set<String> expandKeywords(String[] bases) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String base : bases) {
            String value = base.trim();
            if (value.length() == 0) {
                continue;
            }
            String normalized = normalizeMerchant(value);
            out.add(value);
            out.add(normalized);
            out.add(normalized.replace(" ", ""));
            out.add(normalized.replace("-", ""));
            out.add(value + "店");
            out.add(value + " 支店");
            out.add(value + " paypay");
            out.add(value + " カード");
        }
        return out;
    }

    private static void requireHeader(List<String[]> rows, String... expected) throws Exception {
        if (rows.isEmpty()) {
            throw new Exception("CSV 为空");
        }
        String[] header = rows.get(0);
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(cell(header, i).trim())) {
                throw new Exception("CSV 表头不符合预期");
            }
        }
    }

    private static List<String[]> parseCsv(String text) {
        ArrayList<String[]> rows = new ArrayList<String[]>();
        ArrayList<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String source = text.replace("\uFEFF", "");
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row.toArray(new String[row.size()]));
                row = new ArrayList<String>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        row.add(cell.toString());
        boolean hasData = false;
        for (String value : row) {
            if (value.length() > 0) {
                hasData = true;
                break;
            }
        }
        if (hasData) {
            rows.add(row.toArray(new String[row.size()]));
        }
        return rows;
    }

    private static String cell(String[] row, int index) {
        return index < row.length ? row[index] : "";
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(delimiter);
            }
            out.append(values.get(i));
        }
        return out.toString();
    }

    public static class RuleGroup {
        public final String groupId;
        public final String groupName;
        public final int order;
        public boolean imported;
        public final LinkedHashSet<String> keywords = new LinkedHashSet<String>();

        RuleGroup(String groupId, String groupName, int order, boolean imported) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.order = order;
            this.imported = imported;
        }

        RuleGroup copy() {
            RuleGroup copy = new RuleGroup(groupId, groupName, order, imported);
            copy.keywords.addAll(keywords);
            return copy;
        }
    }

    public static class CategoryMapping {
        public final String groupId;
        public final String major;
        public final String minor;

        public CategoryMapping(String groupId, String major, String minor) {
            this.groupId = groupId == null ? "" : groupId;
            this.major = major == null ? "" : major;
            this.minor = minor == null ? "" : minor;
        }
    }

    public static class MerchantOverride {
        public final String merchantKey;
        public final String displayName;
        public final String major;
        public final String minor;

        MerchantOverride(String merchantKey, String displayName, String major, String minor) {
            this.merchantKey = merchantKey == null ? "" : merchantKey;
            this.displayName = displayName == null ? "" : displayName;
            this.major = major == null ? "" : major;
            this.minor = minor == null ? "" : minor;
        }
    }

    public static class CategoryResult {
        public final String major;
        public final String minor;
        public final String groupId;
        public final String source;

        CategoryResult(String major, String minor, String groupId, String source) {
            this.major = major;
            this.minor = minor;
            this.groupId = groupId;
            this.source = source;
        }
    }
}
