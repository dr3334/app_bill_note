package dev.inkledger.fixture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Synthetic data ONLY. This APK never belongs in the public artifacts directory. */
public final class ReceiptFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) { super.onCreate(state); render(getIntent()); }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); render(intent); }

    private String value(Intent intent, String key, String fallback) {
        String v = intent.getStringExtra(key);
        return v == null ? fallback : v;
    }

    private void render(Intent intent) {
        String mode = value(intent, "mode", "nodes");
        String amount = value(intent, "amount", "12.34");
        String transaction = value(intent, "transaction", "202610180000000001");
        String status = value(intent, "status", "付款成功");
        String time = value(intent, "time", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")));
        String[] lines = {"采集测试夹具 · 非支付宝", status, "实付金额", "￥" + amount,
                "商家：合成测试商家", "交易号：" + transaction, "交易时间：" + time, "本页仅生成合成数据，不执行真实支付"};
        if (mode.equals("notify")) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("fixture", "合成通知", NotificationManager.IMPORTANCE_DEFAULT));
            String body = status + "\n实付金额：" + amount + "元\n商家：合成测试商家\n交易号：" + transaction + "\n交易时间：" + time;
            nm.notify(intent.getIntExtra("notificationId", 101), new Notification.Builder(this, "fixture")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("合成付款通知")
                    .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).build());
            showText(new String[]{"采集测试夹具 · 非支付宝", "已发送合成通知", "该页面没有付款凭据"});
        } else if (mode.equals("canvas")) {
            setContentView(new ReceiptCanvas(this, lines));
        } else if (mode.equals("secure")) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            setContentView(new ReceiptCanvas(this, lines));
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            showText(lines);
        }
    }

    private void showText(String[] lines) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 64, 32, 32);
        root.setBackgroundColor(Color.WHITE);
        for (String line : lines) {
            TextView label = new TextView(this);
            label.setText(line); label.setTextColor(Color.BLACK); label.setTextSize(22);
            label.setPadding(0, 16, 0, 16);
            root.addView(label);
        }
        setContentView(root);
    }

    /** No accessibility text: tests the explicit one-shot screenshot path. */
    private static final class ReceiptCanvas extends View {
        private final String[] lines;
        ReceiptCanvas(Context context, String[] lines) { super(context); this.lines = lines; }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(48);
            for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], 52, 140 + i * 100, p);
        }
    }
}
