package com.hoshitsuki.paypayledger;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jxl.Workbook;
import jxl.write.Label;
import jxl.write.Number;
import jxl.write.WritableSheet;
import jxl.write.WritableWorkbook;

public class MainActivity extends Activity {
    private static final int REQUEST_IMAGES = 41;
    private static final int REQUEST_WRITE_STORAGE = 42;

    private final int paper = Color.rgb(244, 241, 236);
    private final int card = Color.rgb(253, 251, 247);
    private final int ink = Color.rgb(70, 67, 63);
    private final int muted = Color.rgb(135, 130, 122);
    private final int sage = Color.rgb(142, 167, 160);
    private final int clay = Color.rgb(201, 175, 166);
    private final int sand = Color.rgb(222, 211, 194);

    private final ArrayList<BillItem> bills = new ArrayList<BillItem>();
    private final Set<String> seenKeys = new HashSet<String>();
    private final ArrayList<Uri> pendingUris = new ArrayList<Uri>();

    private TextRecognizer recognizer;
    private LinearLayout listArea;
    private TextView statusText;
    private TextView countText;
    private ProgressBar progressBar;
    private Button importButton;
    private Button exportButton;
    private int processedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recognizer = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
        buildUi();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(paper);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("PayPay 自动记账", 30, ink, true);
        root.addView(title);

        TextView subtitle = text("从 PayPay 使用记录截图中提取账单，并生成一木记账导入表。", 15, muted, false);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        LinearLayout hero = panel(dp(26));
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(hero, matchWrap());

        TextView mark = text("PAY", 20, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(sage, dp(20), 0));
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(82), dp(82));
        hero.addView(mark, markLp);

        TextView heroTitle = text("导入截图", 24, ink, true);
        heroTitle.setGravity(Gravity.CENTER);
        heroTitle.setPadding(0, dp(20), 0, dp(4));
        hero.addView(heroTitle);

        TextView heroSub = text("可一次选择多张，重叠截图会自动去重。", 14, muted, false);
        heroSub.setGravity(Gravity.CENTER);
        hero.addView(heroSub);

        importButton = new Button(this);
        importButton.setText("选择 PayPay 截图");
        importButton.setTextSize(18);
        importButton.setTextColor(Color.WHITE);
        importButton.setAllCaps(false);
        importButton.setTypeface(Typeface.DEFAULT_BOLD);
        importButton.setBackground(round(sage, dp(18), 0));
        importButton.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        importLp.setMargins(0, dp(22), 0, 0);
        hero.addView(importButton, importLp);
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openImagePicker();
            }
        });

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsLp.setMargins(0, dp(16), 0, 0);
        root.addView(stats, statsLp);

        countText = statCard("0", "已识别账单");
        stats.addView(countText, new LinearLayout.LayoutParams(0, dp(92), 1));
        TextView ruleText = statCard("11", "模板列");
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(0, dp(92), 1);
        ruleLp.setMargins(dp(12), 0, 0, 0);
        stats.addView(ruleText, ruleLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        progressLp.setMargins(0, dp(16), 0, 0);
        root.addView(progressBar, progressLp);

        statusText = text("等待导入截图", 14, muted, false);
        statusText.setPadding(0, dp(12), 0, dp(16));
        root.addView(statusText);

        exportButton = new Button(this);
        exportButton.setText("重新导出表格");
        exportButton.setTextSize(16);
        exportButton.setTextColor(ink);
        exportButton.setAllCaps(false);
        exportButton.setBackground(round(sand, dp(14), 0));
        exportButton.setEnabled(false);
        root.addView(exportButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportBills();
            }
        });

        TextView section = text("识别结果", 20, ink, true);
        section.setPadding(0, dp(24), 0, dp(10));
        root.addView(section);

        listArea = new LinearLayout(this);
        listArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(listArea, matchWrap());

        setContentView(scrollView);
    }

    private TextView statCard(String number, String label) {
        TextView tv = text(number + "\n" + label, 15, ink, true);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(dp(3), 1.0f);
        tv.setBackground(round(card, dp(18), Color.argb(45, 70, 67, 63)));
        return tv;
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_IMAGES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGES || resultCode != RESULT_OK || data == null) {
            return;
        }
        pendingUris.clear();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                pendingUris.add(clipData.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            pendingUris.add(data.getData());
        }
        for (Uri uri : pendingUris) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        processImages();
    }

    private void processImages() {
        bills.clear();
        seenKeys.clear();
        processedCount = 0;
        listArea.removeAllViews();
        exportButton.setEnabled(false);
        importButton.setEnabled(false);
        statusText.setText("正在识别 0/" + pendingUris.size());
        progressBar.setProgress(0);
        processNextImage();
    }

    private void processNextImage() {
        if (processedCount >= pendingUris.size()) {
            finishProcessing();
            return;
        }
        final Uri uri = pendingUris.get(processedCount);
        try {
            final InputImage image = InputImage.fromFilePath(this, uri);
            recognizer.process(image)
                    .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text text) {
                            addParsedBills(parsePayPayText(text, image.getWidth()));
                        }
                    })
                    .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                        @Override
                        public void onFailure(Exception e) {
                            Toast.makeText(MainActivity.this, "有一张截图识别失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<Text>() {
                        @Override
                        public void onComplete(com.google.android.gms.tasks.Task<Text> task) {
                            processedCount++;
                            int progress = pendingUris.isEmpty() ? 0 : (processedCount * 100 / pendingUris.size());
                            progressBar.setProgress(progress);
                            statusText.setText("正在识别 " + processedCount + "/" + pendingUris.size());
                            processNextImage();
                        }
                    });
        } catch (IOException e) {
            processedCount++;
            processNextImage();
        }
    }

    private void addParsedBills(List<BillItem> parsed) {
        for (BillItem item : parsed) {
            if (seenKeys.add(item.key())) {
                bills.add(item);
            }
        }
        countText.setText(bills.size() + "\n已识别账单");
        renderBills();
    }

    private void finishProcessing() {
        importButton.setEnabled(true);
        Collections.sort(bills, new Comparator<BillItem>() {
            @Override
            public int compare(BillItem a, BillItem b) {
                return (a.date + " " + a.time).compareTo(b.date + " " + b.time);
            }
        });
        renderBills();
        countText.setText(bills.size() + "\n已识别账单");
        if (bills.isEmpty()) {
            statusText.setText("没有识别到可导入的账单，请确认截图是 PayPay 交易履历列表。");
            return;
        }
        exportButton.setEnabled(true);
        statusText.setText("识别完成，正在保存到 Download 文件夹");
        exportBills();
    }

    private List<BillItem> parsePayPayText(Text text, int imageWidth) {
        ArrayList<LineItem> lines = new ArrayList<LineItem>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                String value = clean(line.getText());
                if (box != null && value.length() > 0) {
                    lines.add(new LineItem(value, box));
                }
            }
        }
        Collections.sort(lines, new Comparator<LineItem>() {
            @Override
            public int compare(LineItem a, LineItem b) {
                if (Math.abs(a.box.top - b.box.top) > 10) {
                    return a.box.top - b.box.top;
                }
                return a.box.left - b.box.left;
            }
        });

        ArrayList<BillItem> result = new ArrayList<BillItem>();
        for (LineItem amountLine : lines) {
            Integer amount = extractAmount(amountLine.text);
            if (amount == null || amountLine.centerX() < imageWidth * 0.42f) {
                continue;
            }
            LineItem dateLine = findDateLine(lines, amountLine, imageWidth);
            if (dateLine == null) {
                continue;
            }
            String[] dateTime = extractDateTime(dateLine.text);
            if (dateTime == null) {
                continue;
            }
            String merchant = findMerchant(lines, amountLine, dateLine, imageWidth);
            if (merchant.length() == 0) {
                merchant = clean(amountLine.text.replaceAll("[0-9０-９,，]+\\s*円", ""));
            }
            if (merchant.length() == 0 || isNoise(merchant)) {
                continue;
            }
            Category category = classify(merchant, amount);
            result.add(new BillItem(merchant, dateTime[0], dateTime[1], amount, category.major, category.minor));
        }
        return result;
    }

    private LineItem findDateLine(List<LineItem> lines, LineItem amountLine, int imageWidth) {
        LineItem best = null;
        int bestScore = Integer.MAX_VALUE;
        for (LineItem line : lines) {
            if (line.centerX() > imageWidth * 0.82f) {
                continue;
            }
            if (extractDateTime(line.text) == null) {
                continue;
            }
            int dy = Math.abs(line.centerY() - amountLine.centerY());
            if (dy > 210 || line.centerY() < amountLine.centerY() - 30) {
                continue;
            }
            int score = dy + Math.abs(line.box.left - amountLine.box.left) / 6;
            if (score < bestScore) {
                best = line;
                bestScore = score;
            }
        }
        return best;
    }

    private String findMerchant(List<LineItem> lines, LineItem amountLine, LineItem dateLine, int imageWidth) {
        ArrayList<LineItem> candidates = new ArrayList<LineItem>();
        for (LineItem line : lines) {
            if (line.centerX() > imageWidth * 0.78f) {
                continue;
            }
            if (line.box.top < amountLine.box.top - 75 || line.box.top > dateLine.box.top + 10) {
                continue;
            }
            if (line == amountLine || line == dateLine || isNoise(line.text)
                    || extractAmount(line.text) != null || extractDateTime(line.text) != null) {
                continue;
            }
            candidates.add(line);
        }
        if (candidates.isEmpty()) {
            return "";
        }
        Collections.sort(candidates, new Comparator<LineItem>() {
            @Override
            public int compare(LineItem a, LineItem b) {
                return a.box.top - b.box.top;
            }
        });
        return candidates.get(0).text;
    }

    private Category classify(String merchant, int amount) {
        String m = normalize(merchant).toLowerCase(Locale.ROOT);
        if (containsAny(m, "映画", "cinema", "toho", "イオンシネマ", "ユナイテッドシネマ")) {
            return new Category("休闲娱乐", "电影娱乐");
        }
        if (containsAny(m, "チケ", "ticket", "チケット", "アーティスト", "ライブ", "コンサート", "ローチケ", "ぴあ", "公園", "景区", "入場券")) {
            return new Category("休闲娱乐", "门票演出");
        }
        if (containsAny(m, "docomo", "ドコモ", "au", "softbank", "ソフトバンク", "uq", "ymobile", "楽天モバイル", "通信", "光", "internet")) {
            return new Category("居家生活", "话费宽带");
        }
        if (containsAny(m, "家賃", "房租", "賃貸", "rent")) {
            return new Category("居家生活", "房租");
        }
        if (containsAny(m, "市役所", "区役所", "役場", "水道", "電気", "ガス", "税", "公共", "年金", "保険")) {
            return new Category("居家生活", "公共服务");
        }
        if (containsAny(m, "jr", "地下鉄", "名鉄", "近鉄", "bus", "バス", "鉄道", "交通", "タクシー", "uber", "suica", "pasmo", "manaca")) {
            return new Category("出行交通", "公共交通");
        }
        if (containsAny(m, "スーパー", "イオン", "aeon", "バロー", "valor", "familymart", "ファミリーマート", "セブン", "ローソン", "コンビニ", "業務スーパー", "ドンキ")) {
            return new Category("食品餐饮", "食材采购");
        }
        if (containsAny(m, "すき家", "マクドナルド", "吉香楼", "料理", "食堂", "restaurant", "レストラン", "カフェ", "喫茶", "ラーメン", "餃子", "寿司", "焼肉", "居酒屋", "バーガー", "kfc", "ケンタッキー", "松屋", "吉野家")) {
            if (amount > 3000) {
                return new Category("休闲娱乐", "外出吃饭");
            }
            return new Category("食品餐饮", "日常正餐");
        }
        if (containsAny(m, "openai", "chatgpt", "apple", "google", "netflix", "spotify", "amazon prime", "会员", "subscription", "subscr")) {
            return new Category("居家生活", "App会员");
        }
        return new Category("购物消费", "日用百货");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Integer extractAmount(String text) {
        Matcher matcher = Pattern.compile("([0-9０-９][0-9０-９,，]*)\\s*円").matcher(normalize(text));
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1).replace(",", "").replace("，", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String[] extractDateTime(String text) {
        Matcher matcher = Pattern.compile("(20[0-9]{2})年([0-9]{1,2})月([0-9]{1,2})日\\s*([0-9]{1,2})時([0-9]{1,2})分")
                .matcher(normalize(text));
        if (!matcher.find()) {
            return null;
        }
        String date = String.format(Locale.US, "%04d-%02d-%02d",
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
        String time = String.format(Locale.US, "%02d:%02d",
                Integer.parseInt(matcher.group(4)),
                Integer.parseInt(matcher.group(5)));
        return new String[]{date, time};
    }

    private String clean(String text) {
        return normalize(text).replaceAll("\\s+", " ").trim();
    }

    private String normalize(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') {
                out.append((char) ('0' + c - '０'));
            } else if (c == '，') {
                out.append(',');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private boolean isNoise(String text) {
        String m = normalize(text).toLowerCase(Locale.ROOT);
        return m.length() < 2
                || m.equals("r")
                || m.contains("取引履歴")
                || m.contains("paypayカード")
                || m.contains("paypay残高")
                || m.contains("クレジット")
                || m.contains("支払い")
                || m.contains("visa")
                || m.contains("すべて")
                || m.contains("kb/s")
                || m.matches("20[0-9]{2}年[0-9]{1,2}月")
                || m.matches("[0-9:]+")
                || m.matches("[0-9]+");
    }

    private void renderBills() {
        listArea.removeAllViews();
        if (bills.isEmpty()) {
            TextView empty = text("导入后会在这里看到每一条识别出的账单。", 14, muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(22), 0, dp(22));
            empty.setBackground(round(card, dp(16), 0));
            listArea.addView(empty, matchWrap());
            return;
        }
        for (BillItem bill : bills) {
            LinearLayout row = panel(dp(16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(10));
            listArea.addView(row, lp);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(top, matchWrap());

            TextView name = text(bill.merchant, 17, ink, true);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView amount = text(String.format(Locale.US, "%,d円", bill.amount), 18, ink, true);
            amount.setGravity(Gravity.RIGHT);
            top.addView(amount);

            TextView meta = text(bill.date + " " + bill.time + "  ·  " + bill.major + " / " + bill.minor, 13, muted, false);
            meta.setPadding(0, dp(8), 0, 0);
            row.addView(meta);
        }
    }

    private void exportBills() {
        if (bills.isEmpty()) {
            Toast.makeText(this, "没有可导出的账单", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
            return;
        }
        try {
            String fileName = "PayPay_一木记账_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".xls";
            byte[] workbook = XlsExporter.create(bills);
            writeDownload(fileName, workbook);
            statusText.setText("已保存到 Download/" + fileName);
            Toast.makeText(this, "导出完成", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            statusText.setText("导出失败：" + e.getMessage());
            Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            exportBills();
        }
    }

    private void writeDownload(String fileName, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.ms-excel");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法创建下载文件");
            }
            OutputStream outputStream = resolver.openOutputStream(uri);
            if (outputStream == null) {
                throw new IOException("无法写入下载文件");
            }
            outputStream.write(data);
            outputStream.close();
            return;
        }
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw new IOException("无法创建 Download 文件夹");
        }
        FileOutputStream outputStream = new FileOutputStream(new File(downloads, fileName));
        outputStream.write(data);
        outputStream.close();
    }

    private LinearLayout panel(int padding) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackground(round(card, dp(22), Color.argb(35, 70, 67, 63)));
        return panel;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(true);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private GradientDrawable round(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recognizer.close();
    }

    private static class LineItem {
        final String text;
        final Rect box;

        LineItem(String text, Rect box) {
            this.text = text;
            this.box = box;
        }

        int centerX() {
            return box.left + box.width() / 2;
        }

        int centerY() {
            return box.top + box.height() / 2;
        }
    }

    private static class Category {
        final String major;
        final String minor;

        Category(String major, String minor) {
            this.major = major;
            this.minor = minor;
        }
    }

    private static class BillItem {
        final String merchant;
        final String date;
        final String time;
        final int amount;
        final String major;
        final String minor;

        BillItem(String merchant, String date, String time, int amount, String major, String minor) {
            this.merchant = merchant;
            this.date = date;
            this.time = time;
            this.amount = amount;
            this.major = major;
            this.minor = minor;
        }

        String key() {
            return merchant + "|" + date + "|" + time + "|" + amount;
        }
    }

    private static class XlsExporter {
        static byte[] create(List<BillItem> bills) throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WritableWorkbook workbook = Workbook.createWorkbook(bytes);
            WritableSheet sheet = workbook.createSheet("Sheet1", 0);
            String[] headers = new String[]{"日期", "收支类型", "金额", "类别", "二级分类", "所属账本", "收支账户", "备注", "标签", "地址"};
            for (int i = 0; i < headers.length; i++) {
                sheet.addCell(new Label(i, 0, headers[i]));
            }
            for (int i = 0; i < bills.size(); i++) {
                BillItem bill = bills.get(i);
                int row = i + 1;
                sheet.addCell(new Label(0, row, bill.date + " " + bill.time));
                sheet.addCell(new Label(1, row, "支出"));
                sheet.addCell(new Number(2, row, bill.amount));
                sheet.addCell(new Label(3, row, bill.major));
                sheet.addCell(new Label(4, row, bill.minor));
                sheet.addCell(new Label(5, row, "日常账本"));
                sheet.addCell(new Label(6, row, "PayPay"));
                sheet.addCell(new Label(7, row, bill.merchant));
                sheet.addCell(new Label(8, row, ""));
                sheet.addCell(new Label(9, row, ""));
            }
            workbook.write();
            workbook.close();
            return bytes.toByteArray();
        }
    }
}
