package dev.scout.udfpshbm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Minimal reflection helpers.
 *
 * The modern libxposed API does not provide XposedHelpers, and modules
 * targeting API 102 cannot call the legacy de.robv.android.xposed classes, so
 * the few lookups the module needs live here.
 */
final class Reflect {

    private Reflect() {
    }

    static Class<?> findClass(String name, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(name, false, classLoader);
    }

    static Field findField(Class<?> clazz, String name) {
        for (Class<?> current = clazz;
                current != null;
                current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Keep looking in the superclass.
            }
        }
        return null;
    }

    static Object getObjectField(Object object, String name)
            throws IllegalAccessException {
        Field field = findField(object.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldError(name + " in " + object.getClass().getName());
        }
        return field.get(object);
    }

    static boolean getBooleanField(Object object, String name)
            throws IllegalAccessException {
        Field field = findField(object.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldError(name + " in " + object.getClass().getName());
        }
        return field.getBoolean(object);
    }

    static int getIntField(Object object, String name)
            throws IllegalAccessException {
        Field field = findField(object.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldError(name + " in " + object.getClass().getName());
        }
        return field.getInt(object);
    }

    /**
     * Finds a method by name and argument values, looking at the class and its
     * superclasses. Overloads are resolved by arity and assignability.
     */
    static Method findMethod(Class<?> clazz, String name, Object... args) {
        Method fallback = null;
        for (Class<?> current = clazz;
                current != null;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) {
                    continue;
                }
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != args.length) {
                    continue;
                }
                if (fallback == null) {
                    fallback = method;
                }
                if (!matches(parameterTypes, args)) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
        }
        if (fallback != null) {
            fallback.setAccessible(true);
        }
        return fallback;
    }

    static Object callMethod(Object object, String name, Object... args)
            throws Exception {
        Method method = findMethod(object.getClass(), name, args);
        if (method == null) {
            throw new NoSuchMethodException(name + " in "
                    + object.getClass().getName());
        }
        return method.invoke(object, args);
    }

    private static boolean matches(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!box(parameterTypes[i]).isInstance(arg)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return Void.class;
    }
}
