package com.mongodb.agentengine.runner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolves an {@code agent.yaml} entrypoint to the app it names. */
public final class AppLoader {

  private AppLoader() {}

  /**
   * Loads the app named by {@code fully.qualified.Class:member}, where {@code member} is a static
   * field or a static no-argument method that yields a {@link RunnableApp}.
   *
   * @throws IllegalArgumentException if the entrypoint is malformed, or names something that is not
   *     a runnable app
   * @throws ReflectiveOperationException if the class or member cannot be loaded
   */
  public static RunnableApp load(String entrypoint) throws ReflectiveOperationException {
    int colon = entrypoint.indexOf(':');
    if (colon <= 0 || colon == entrypoint.length() - 1) {
      throw new IllegalArgumentException("Invalid entrypoint '" + entrypoint + "': expected 'fully.qualified.Class:member'");
    }
    String className = entrypoint.substring(0, colon).trim();
    String member = entrypoint.substring(colon + 1).trim();
    Class<?> type = Class.forName(className, true, Thread.currentThread().getContextClassLoader());

    Object value = read(type, member);
    if (!(value instanceof RunnableApp app)) {
      throw new IllegalArgumentException(entrypoint + " must be a " + RunnableApp.class.getName() + ", got " + (value == null ? "null" : value.getClass().getName()));
    }
    return app;
  }

  private static Object read(Class<?> type, String member) throws ReflectiveOperationException {
    try {
      Field field = type.getDeclaredField(member);
      requireStatic(field.getModifiers(), type, member);
      field.setAccessible(true);
      return field.get(null);
    } catch (NoSuchFieldException notAField) {
      try {
        Method method = type.getDeclaredMethod(member);
        requireStatic(method.getModifiers(), type, member);
        method.setAccessible(true);
        return method.invoke(null);
      } catch (NoSuchMethodException notAMethod) {
        throw new NoSuchFieldException("Class " + type.getName() + " has no static field or no-argument method '" + member + "'");
      }
    }
  }

  private static void requireStatic(int modifiers, Class<?> type, String member) {
    if (!Modifier.isStatic(modifiers)) {
      throw new IllegalArgumentException(type.getName() + "." + member + " must be static");
    }
  }
}
