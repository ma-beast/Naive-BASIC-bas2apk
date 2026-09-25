package com.naivework.basapkmaker;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import java.io.*;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

public class MainActivity extends Activity {
    private TextView title;
    private TextView status;
    private ListView list;
    private File currentDir;
    private ArrayList<File> entries = new ArrayList<File>();
    private ArrayAdapter<String> adapter;

    public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        File start = Environment.getExternalStorageDirectory();
        if (!start.exists() || !start.isDirectory()) {
            status.setText("SD-карта не найдена.");
            return;
        }
        showDirectory(start);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(8,8,8,8);

        title = new TextView(this);
        title.setText("Naive BASIC bas2apk 1.0");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));

        status = new TextView(this);
        status.setText("Открытие SD-карты...");
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(-1,-2));

        list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1,0,1));

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= entries.size()) return;
                File f = entries.get(position);
                if (f.getName().equals("..")) {
                    File p = currentDir.getParentFile();
                    File rootDir = Environment.getExternalStorageDirectory();
                    if (p != null && !currentDir.equals(rootDir)) showDirectory(p);
                } else if (f.isDirectory()) {
                    showDirectory(f);
                } else if (f.getName().toLowerCase().endsWith(".bas")) {
                    exportBas(f);
                }
            }
        });
        setContentView(root);
    }

    private void showDirectory(File dir) {
        currentDir = dir;
        title.setText("Naive BASIC bas2apk 1.0\n" + dir.getAbsolutePath());
        adapter.clear();
        entries.clear();

        if (!dir.equals(Environment.getExternalStorageDirectory())) {
            File up = new File(dir, "..").getAbsoluteFile().getParentFile();
            // Special marker; actual parent is obtained from currentDir.
            entries.add(new File(dir, ".."));
            adapter.add("..  [вверх]");
        }

        File[] files = dir.listFiles();
        if (files == null) {
            status.setText("Не удалось прочитать папку.");
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File f : files) {
            if (f.isHidden()) continue;
            if (f.isDirectory()) {
                entries.add(f);
                adapter.add("[DIR] " + f.getName());
            } else if (f.getName().toLowerCase().endsWith(".bas")) {
                entries.add(f);
                adapter.add(f.getName());
            }
        }
        status.setText("Выберите .BAS программу.");
    }

    private void exportBas(final File bas) {
        status.setText("Создание APK...\n" + bas.getAbsolutePath());
        new Thread(new Runnable() {
            public void run() {
                try { makeApk(bas); }
                catch (final Exception e) {
                    runOnUiThread(new Runnable() { public void run() {
                        status.setText("ОШИБКА: " + message(e));
                        Toast.makeText(MainActivity.this,"Не удалось создать APK",Toast.LENGTH_LONG).show();
                    }});
                }
            }
        }).start();
    }

    private void makeApk(File bas) throws Exception {
        File dir=bas.getParentFile();
        if(dir==null || !dir.exists()) throw new IOException("Нет папки исходного файла");
        String name=bas.getName();
        String base=name.substring(0,name.length()-4);
        File out=new File(dir,base+".apk");
        byte[] program=readFile(bas);

        File template=new File(getCacheDir(),"NaiveWORK_0.2G.apk");
        copyAsset("NaiveWORK_0.2G.apk",template);

        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(
            new PKCS8EncodedKeySpec(readAsset("export_private.pk8")));
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(readAsset("export_cert.der")));

        String packageName = makePackageName(base);
        V1Signer.make(template,out,program,base,packageName,key,cert);

        final String result=out.getAbsolutePath();
        runOnUiThread(new Runnable() { public void run() {
            status.setText("ГОТОВО:\n"+result);
            Toast.makeText(MainActivity.this,"APK создан",Toast.LENGTH_LONG).show();
        }});
    }


    private String makePackageName(String base) {
        String lower = base.toLowerCase(Locale.US);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case 'а': b.append("a"); break;
                case 'б': b.append("b"); break;
                case 'в': b.append("v"); break;
                case 'г': b.append("g"); break;
                case 'д': b.append("d"); break;
                case 'е': case 'ё': b.append("e"); break;
                case 'ж': b.append("zh"); break;
                case 'з': b.append("z"); break;
                case 'и': b.append("i"); break;
                case 'й': b.append("j"); break;
                case 'к': b.append("k"); break;
                case 'л': b.append("l"); break;
                case 'м': b.append("m"); break;
                case 'н': b.append("n"); break;
                case 'о': b.append("o"); break;
                case 'п': b.append("p"); break;
                case 'р': b.append("r"); break;
                case 'с': b.append("s"); break;
                case 'т': b.append("t"); break;
                case 'у': b.append("u"); break;
                case 'ф': b.append("f"); break;
                case 'х': b.append("h"); break;
                case 'ц': b.append("c"); break;
                case 'ч': b.append("ch"); break;
                case 'ш': b.append("sh"); break;
                case 'щ': b.append("sch"); break;
                case 'ъ': case 'ь': b.append("0"); break;
                case 'ы': b.append("y"); break;
                case 'э': b.append("e"); break;
                case 'ю': b.append("yu"); break;
                case 'я': b.append("ya"); break;
                default:
                    if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                        b.append(c);
                    } else {
                        b.append('0');
                    }
            }
        }
        String slug = b.toString();
        if (slug.length() == 0) slug = "0";
        if (!((slug.charAt(0) >= 'a' && slug.charAt(0) <= 'z'))) {
            slug = "p" + slug;
        }
        if (slug.length() > 120) slug = slug.substring(0,120);
        return "com.naivework." + slug;
    }

    private byte[] readFile(File f) throws Exception {
        InputStream in=new FileInputStream(f); ByteArrayOutputStream b=new ByteArrayOutputStream();
        copy(in,b); in.close(); return b.toByteArray();
    }
    private byte[] readAsset(String n) throws Exception { return readStream(getAssets().open(n)); }
    private byte[] readStream(InputStream in) throws Exception {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); copy(in,b); in.close(); return b.toByteArray();
    }
    private void copyAsset(String n,File out) throws Exception {
        InputStream in=getAssets().open(n); FileOutputStream f=new FileOutputStream(out);
        copy(in,f); in.close(); f.close();
    }
    private static void copy(InputStream in,OutputStream out) throws Exception {
        byte[] b=new byte[8192]; int n; while((n=in.read(b))!=-1) out.write(b,0,n);
    }
    private String message(Exception e) { return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
}
