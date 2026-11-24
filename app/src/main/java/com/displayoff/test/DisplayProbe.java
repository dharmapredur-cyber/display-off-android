package com.displayoff.test;

import android.os.IBinder;
import android.os.SystemClock;
import android.system.Os;
import java.lang.reflect.Method;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;

/** Executed only by the local ADB shell via app_process. No arbitrary commands. */
public final class DisplayProbe {
    private static final String LOCK="/data/local/tmp/display-off-test-v1.lock";
    private final IBinder token;
    private final Method power;
    private DisplayProbe() throws Exception {
        if (Os.getuid() != 2000) throw new SecurityException("Shell UID required");
        Class<?> surface = Class.forName("android.view.SurfaceControl");
        power = surface.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        Class<?> control = surface;
        try { control.getMethod("getPhysicalDisplayIds"); }
        catch (NoSuchMethodException e) {
            // Android 14 moved display enumeration into services.jar.
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = factory.getDeclaredMethod("createClassLoader", String.class,
                String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader cl = (ClassLoader) create.invoke(null, "/system/framework/services.jar",
                null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
            control = cl.loadClass("com.android.server.display.DisplayControl");
            Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            load.setAccessible(true);
            load.invoke(Runtime.getRuntime(), control, "android_servers");
        }
        long[] ids = (long[]) control.getMethod("getPhysicalDisplayIds").invoke(null);
        if (ids == null || ids.length != 1) throw new IllegalStateException("Exactly one physical display required");
        token = (IBinder) control.getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[0]);
        if (token == null || !token.isBinderAlive()) throw new IllegalStateException("No live display token");
    }
    private void set(int mode) throws Exception {
        Object result = power.invoke(null, token, mode);
        if (Boolean.FALSE.equals(result)) throw new IOException("Display API rejected mode " + mode);
    }
    private void restore() {
        for (int i=0; i<3; i++) {
            try { set(2); System.out.println("ON command sent"); System.out.flush(); }
            catch (Throwable e) { e.printStackTrace(); }
            if (i<2) SystemClock.sleep(700);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Mode required");
        DisplayProbe d = new DisplayProbe();
        if ("guard".equals(args[0])) {
            d.set(2); // Fail before readiness if the ON invocation throws.
            System.out.println("READY"); System.out.flush();
            SystemClock.sleep(15000);
            d.restore();
            return;
        }
        if ("check".equals(args[0])) { d.set(2); System.out.println("CHECK_OK"); return; }
        if (!"test".equals(args[0])) throw new IllegalArgumentException("Unknown mode");
        try (RandomAccessFile f=new RandomAccessFile(LOCK,"rw");
             FileLock lock=f.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Another test is running");
            d.set(2);
            String cp=System.getenv("CLASSPATH");
            if(cp==null || cp.isEmpty()) throw new IOException("Missing CLASSPATH");
            ProcessBuilder pb=new ProcessBuilder("/system/bin/app_process","/system/bin",
                    DisplayProbe.class.getName(),"guard");
            pb.environment().put("CLASSPATH",cp);
            pb.redirectError(ProcessBuilder.Redirect.appendTo(new File("/data/local/tmp/display-off-guard.log")));
            Process guard=pb.start();
            BufferedReader reader=new BufferedReader(new InputStreamReader(guard.getInputStream()));
            long deadline=SystemClock.elapsedRealtime()+5000;
            boolean ready=false;
            while(SystemClock.elapsedRealtime()<deadline && guard.isAlive()) {
                if(reader.ready()) { ready="READY".equals(reader.readLine()); break; }
                SystemClock.sleep(50);
            }
            if(!ready || !guard.isAlive()) {
                guard.destroy(); throw new IOException("Watchdog not ready. Display not switched off.");
            }
            System.out.println("GUARD_READY"); System.out.flush();
            try {
                d.set(0);
                System.out.println("OFF command sent"); System.out.flush();
                SystemClock.sleep(10000);
            } finally {
                d.restore();
            }
            guard.waitFor(10,TimeUnit.SECONDS);
            System.out.println("TEST_FINISHED"); System.out.flush();
        }
    }
}
