package javax.lang.model;

/**
 * Android has no java.compiler module, so this standard JDK type is simply absent at runtime.
 * ECJ's FileSystem class calls SourceVersion.valueOf("RELEASE_12") in a static initializer with
 * no fallback for the class being missing entirely (only for an unrecognised name), which turns
 * into an uncatchable NoClassDefFoundError the moment FileSystem loads. This stub exists purely
 * so that static initializer links and runs instead of crashing the app — nothing here needs to
 * behave like the real JDK API beyond supporting a normal enum valueOf() call.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6, RELEASE_7,
    RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13, RELEASE_14, RELEASE_15,
    RELEASE_16, RELEASE_17, RELEASE_18, RELEASE_19, RELEASE_20, RELEASE_21, RELEASE_22, RELEASE_23
}
