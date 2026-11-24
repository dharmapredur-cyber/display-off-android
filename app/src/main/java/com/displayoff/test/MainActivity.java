package com.displayoff.test;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.*;
import android.text.InputType;
import io.github.muntashirakon.adb.AdbStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LocalAdb adb;
    private EditText pairPort, pairCode, connectPort;
    private TextView status;
    private Button pair, connect, test, log;
    private boolean verified=false;
    private static final String LOG="/data/local/tmp/display-off-test-v1.log";
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll=new ScrollView(this);
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(20*getResources().getDisplayMetrics().density);
        layout.setPadding(pad,pad,pad,pad);
        scroll.addView(layout); setContentView(scroll);
        addText(layout,"Дисплей — тест 10 секунд",24);
        addText(layout,"Экспериментальная версия 0.1.0. Без root и сторонних приложений. Восстановление на HyperOS не гарантировано. Не тестируйте во время важного звонка; сохраните данные.",16);
        Button settings=button(layout,"Открыть настройки разработчика");
        settings.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}});
        addText(layout,"1. Включите беспроводную отладку. Для сопряжения откройте настройки и это приложение в режиме разделённого экрана. В окне сопряжения введите порт и шестизначный код. IP не нужен.",16);
        pairPort=field(layout,"Порт сопряжения");
        pairCode=field(layout,"Код сопряжения");
        pair=button(layout,"Сопрячь");
        pair.setOnClickListener(v->{
            try {
                int p=port(pairPort); String code=pairCode.getText().toString().trim();
                if(!code.matches("[0-9]{6}"))throw new IllegalArgumentException("Нужен код из 6 цифр");
                verified=false;
                execute(()->{if(adb().isConnected())adb().disconnect(); if(!adb().pair("127.0.0.1",p,code))throw new IOException("Сопряжение отклонено"); return "Сопряжение выполнено. Введите основной порт отладки.";});
                pairCode.setText("");
            }catch(Exception e){show(e.getMessage());}
        });
        addText(layout,"2. Введите основной порт с экрана «Беспроводная отладка» (не порт сопряжения). Затем проверка возможности отправить команду включения.",16);
        connectPort=field(layout,"Основной порт отладки");
        connect=button(layout,"Подключить и проверить");
        connect.setOnClickListener(v->{
            try{int p=port(connectPort); verified=false;
                execute(()->{
                    if(adb().isConnected())adb().disconnect();
                    if(!adb().connect("127.0.0.1",p))throw new IOException("Подключение отклонено");
                    String r=shell(prefix()+" check");
                    if(!r.contains("CHECK_OK"))throw new IOException("Проверка не пройдена: "+r);
                    verified=true; return "Команда включения принята без исключения. Это не гарантия восстановления. Тест доступен.";
                });
            }catch(Exception e){show(e.getMessage());}
        });
        addText(layout,"3. Тест: дисплей выключится примерно на 10 секунд. Затем три команды включения; отдельный резервный процесс повторит включение ещё раз. Если резервный процесс не подготовлен, выключения не будет.",16);
        test=button(layout,"Отключить дисплей на 10 секунд");
        test.setEnabled(false);
        test.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Эксперимент с дисплеем")
            .setMessage("Если экран не включится, попробуйте кнопку питания, затем удерживайте её 10–15 секунд для принудительной перезагрузки. Продолжить?")
            .setNegativeButton("Отмена",null).setPositiveButton("Тест", (a,b2)->{
                verified=false;
                execute(()->{
                    String r=shell("nohup "+prefix()+" test >"+LOG+" 2>&1 </dev/null & echo TEST_LAUNCHED");
                    if(!r.contains("TEST_LAUNCHED"))throw new IOException("Не подтверждён запуск: "+r);
                    Thread.sleep(22000);
                    return "Журнал теста:\n"+shell("cat "+LOG)+"\nПроверьте экран. Перед новым тестом снова подключитесь.";
                });
            }).show());
        log=button(layout,"Прочитать журнал последнего теста");
        log.setOnClickListener(v->execute(()->shell("cat "+LOG)));
        status=addText(layout,"Ожидание подключения.",16);
    }
    private LocalAdb adb() throws Exception { if(adb==null)adb=new LocalAdb(this); return adb; }
    private String prefix() {
        String path=getApplicationInfo().sourceDir.replace("'","'\\''");
        return "env CLASSPATH='"+path+"' /system/bin/app_process /system/bin com.displayoff.test.DisplayProbe";
    }
    private String shell(String command) throws Exception {
        try(AdbStream s=adb().openStream("shell:"+command);
            InputStream in=s.openInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[2048]; int n;
            while((n=in.read(buf))!=-1) { if(n>0)out.write(buf,0,n); if(out.size()>65536)throw new IOException("Слишком длинный ответ"); }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private interface Task { String run() throws Exception; }
    private void execute(Task t) {
        pair.setEnabled(false);connect.setEnabled(false);test.setEnabled(false);log.setEnabled(false);
        show("Выполняется...");
        worker.execute(()->{
            String result;
            try{result=t.run();}catch(Throwable e){verified=false;result="Ошибка: "+e;}
            String text=result;
            runOnUiThread(()->{show(text);pair.setEnabled(true);connect.setEnabled(true);test.setEnabled(verified);log.setEnabled(true);});
        });
    }
    private int port(EditText e){int p=Integer.parseInt(e.getText().toString().trim());if(p<1||p>65535)throw new IllegalArgumentException("Неверный порт");return p;}
    private void show(String s){status.setText(s);}
    private TextView addText(LinearLayout l,String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setPadding(0,10,0,10);l.addView(t);return t;}
    private Button button(LinearLayout l,String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);l.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private EditText field(LinearLayout l,String hint){EditText e=new EditText(this);e.setHint(hint);e.setInputType(InputType.TYPE_CLASS_NUMBER);e.setSingleLine(true);l.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
}
