package dev.kotrace.buildlogic;

import java.lang.invoke.MethodHandles;
import java.util.function.Supplier;

/**
 * A method reference javac compiles to an invokedynamic whose implementation handle names
 * {@code MethodHandles.publicLookup} itself: an ordinary call, though a lambda's bootstrap arguments carry it.
 */
final class ReferencesPublicLookup {
    static final Supplier<Object> LOOKUP = MethodHandles::publicLookup;
}
