# libperigee_engine.so binds its functions by name: Java_org_evsyukov_perigee_engine_NativeEngine_<method>.
# The class and its native methods keep their names, and the native methods are kept even when unused.
# The library calls stage back by name while tracing is on.
-keep class org.evsyukov.perigee.engine.NativeEngine {
    native <methods>;
    public static void stage(int, int, int);
}
