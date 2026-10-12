package org.evsyukov.perigee.engine

object NativeEngine {
    init {
        System.loadLibrary("perigee_engine")
    }

    /** DOM version crengine assigns to newly opened books; xpointers depend on it. */
    external fun domVersion(): Int
}
