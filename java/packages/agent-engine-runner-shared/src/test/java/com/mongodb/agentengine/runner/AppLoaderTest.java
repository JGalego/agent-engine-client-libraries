package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppLoaderTest {

  /** Apps the loader resolves in these tests. */
  public static final class Apps {
    public static final RunnableApp FIELD = new RunnableApp("field");
    static final RunnableApp FACTORY = new RunnableApp("factory");
    public static final String NOT_AN_APP = "text";
    public static final RunnableApp NOT_STATIC_HOLDER = null;
    public final RunnableApp instance = new RunnableApp("instance");

    public static RunnableApp create() {
      return FACTORY;
    }

    private Apps() {}
  }

  private static final String CLASS = AppLoaderTest.class.getName() + "$Apps";

  @Test
  void loadsAStaticField() throws Exception {
    assertSame(Apps.FIELD, AppLoader.load(CLASS + ":FIELD"));
  }

  @Test
  void loadsFromAStaticNoArgumentMethod() throws Exception {
    assertSame(Apps.FACTORY, AppLoader.load(CLASS + ":create"));
  }

  @Test
  void rejectsMembersThatAreNotRunnableApps() {
    assertThrows(IllegalArgumentException.class, () -> AppLoader.load(CLASS + ":NOT_AN_APP"));
    assertThrows(IllegalArgumentException.class, () -> AppLoader.load(CLASS + ":NOT_STATIC_HOLDER"));
  }

  @Test
  void rejectsInstanceMembers() {
    assertThrows(IllegalArgumentException.class, () -> AppLoader.load(CLASS + ":instance"));
  }

  @Test
  void rejectsMalformedEntrypoints() {
    assertThrows(IllegalArgumentException.class, () -> AppLoader.load("no-colon"));
    assertThrows(IllegalArgumentException.class, () -> AppLoader.load(":member"));
    assertThrows(IllegalArgumentException.class, () -> AppLoader.load("com.example.App:"));
  }

  @Test
  void reportsMissingClassesAndMembers() {
    assertThrows(ClassNotFoundException.class, () -> AppLoader.load("com.example.Missing:app"));
    assertThrows(NoSuchFieldException.class, () -> AppLoader.load(CLASS + ":missing"));
  }
}
