package com.cloudwolf.obdgauge;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃捕获：未捕获异常写入私有目录 last_crash.txt，并交给系统默认处理。
 *
 * 车机无法连 ADB 看日志，主界面启动时读取该文件弹窗展示，
 * 便于现场拍照反馈崩溃堆栈。
 */
public final class CrashGuard implements Thread.UncaughtExceptionHandler {

    private static final String FILE = "last_crash.txt";
    private static final int MAX_READ = 6000;
    private static boolean installed;

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    /** 幂等安装；主界面与悬浮窗服务各自调用。 */
    public static void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        Thread.setDefaultUncaughtExceptionHandler(new CrashGuard(context.getApplicationContext()));
    }

    private CrashGuard(Context appContext) {
        this.appContext = appContext;
        this.previous = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String text = "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\n版本: " + versionName()
                    + "\n线程: " + thread.getName()
                    + "\n\n" + sw;
            FileOutputStream fos = new FileOutputStream(new File(appContext.getFilesDir(), FILE));
            fos.write(text.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        }
    }

    private String versionName() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 上次崩溃的堆栈（过长截尾），无记录返回 null。 */
    public static String readLast(Context context) {
        File f = new File(context.getFilesDir(), FILE);
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        try {
            FileInputStream fis = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            fis.close();
            String all = bos.toString("UTF-8");
            if (all.length() > MAX_READ) {
                // 保留结尾：堆栈根部（Caused by / 异常行）最有价值
                all = "…（已截断）\n" + all.substring(all.length() - MAX_READ);
            }
            return all;
        } catch (Exception e) {
            return null;
        }
    }

    /** 清除崩溃记录。 */
    public static void clear(Context context) {
        new File(context.getFilesDir(), FILE).delete();
    }
}
