package org.json;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public class JSONObjectBuilder {
    private MapFactory mapFactory;
    private int initialCapacity = -1;

    // Populate sources
    private JSONObject sourceJSONObject = null;
    private String[] sourceJSONObjectAttributeNames = null;
    private JSONTokener sourceTokenizer = null;
    private Map<?, ?> sourceMap = null;
    private Object sourceBean = null;
    private Set<Object> sourceBeanObjectsRecord = null;
    private String[]  sourceBeanNames = null;

    private Locale locale = null;

    public JSONObject build() {
        if (mapFactory == null)
            mapFactory = mapFactory.DEFAULT;
        if (initialCapacity < 0) {// Not user specified
            int potentialCapacityFromSources = 0;
            if (sourceJSONObjectAttributeNames != null)
                potentialCapacityFromSources += sourceJSONObjectAttributeNames.length;
            if (sourceBeanNames != null)
                potentialCapacityFromSources += sourceBeanNames.length;
            if (sourceBeanObjectsRecord != null)
                potentialCapacityFromSources += sourceBeanObjectsRecord.size();
            if (sourceMap != null)
                potentialCapacityFromSources += sourceMap.size();

            if (potentialCapacityFromSources > 0)
                initialCapacity = potentialCapacityFromSources;
        }

        JSONObject returnValue = new JSONObject(mapFactory.newMap(initialCapacity));

        populateFromJSONObject(returnValue);
        populateFromJSONTokenizer(returnValue);
        populateFromMap(returnValue);
        if (sourceBeanNames != null)
            populateFromBeanWithNames(returnValue);
        else if (sourceBean != null)
            populateFromBeanWithObjectsRecord(returnValue);

        return returnValue;
    }

    public JSONObjectBuilder withMapFactory(MapFactory mapFactory) {
        this.mapFactory = mapFactory;
        return this;
    }

    public JSONObjectBuilder withJSONObject(JSONObject jo, String... names) {
        sourceJSONObject = jo;
        sourceJSONObjectAttributeNames = names;
        return this;
    }

    public JSONObjectBuilder withJSONTokenizer(JSONTokener x) throws JSONException {
        this.sourceTokenizer = x;
        return this;
    }

    public JSONObjectBuilder withMap(Map<?, ?> map) {
        this.sourceMap = map;
        return this;
    }

    public JSONObjectBuilder withSourceBean(Object bean) {
        if (sourceBean != null)
            throw new IllegalStateException("Only one bean source can be specified");
        this.sourceBean = bean;
        return this;
    }

    public JSONObjectBuilder withSourceBean(Object bean, Set<Object> objectsRecord) {
        if (sourceBean != null)
            throw new IllegalStateException("Only one bean source can be specified");
        this.sourceBean = bean;
        this.sourceBeanObjectsRecord = objectsRecord;
        return this;
    }

    private void populateFromJSONObject(JSONObject jo) {
        if (sourceJSONObject == null || sourceJSONObjectAttributeNames == null)
            return;

        for (int i = 0; i < sourceJSONObjectAttributeNames.length; i += 1) {
            try {
                jo.putOnce(sourceJSONObjectAttributeNames[i], sourceJSONObject.opt(sourceJSONObjectAttributeNames[i]));
            } catch (Exception ignore) {
            }
        }
    }

    private void populateFromJSONTokenizer(JSONObject jo) throws JSONException {
        if (sourceTokenizer == null)
            return;

        char c;
        String key;

        if (sourceTokenizer.nextClean() != '{') {
            throw sourceTokenizer.syntaxError("A JSONObject text must begin with '{'");
        }
        for (; ; ) {
            char prev = sourceTokenizer.getPrevious();
            c = sourceTokenizer.nextClean();
            switch (c) {
                case 0:
                    throw sourceTokenizer.syntaxError("A JSONObject text must end with '}'");
                case '}':
                    return;
                case '{':
                case '[':
                    if (prev == '{') {
                        throw sourceTokenizer.syntaxError("A JSON Object can not directly nest another JSON Object or JSON Array.");
                    }
                    // fall through
                default:
                    sourceTokenizer.back();
                    key = sourceTokenizer.nextValue().toString();
            }

            // The key is followed by ':'.

            c = sourceTokenizer.nextClean();
            if (c != ':') {
                throw sourceTokenizer.syntaxError("Expected a ':' after a key");
            }

            // Use syntaxError(..) to include error location

            if (key != null) {
                // Check if key exists
                if (jo.opt(key) != null) {
                    // key already exists
                    throw sourceTokenizer.syntaxError("Duplicate key \"" + key + "\"");
                }
                // Only add value if non-null
                Object value = sourceTokenizer.nextValue();
                if (value != null) {
                    jo.put(key, value);
                }
            }

            // Pairs are separated by ','.

            switch (sourceTokenizer.nextClean()) {
                case ';':
                case ',':
                    if (sourceTokenizer.nextClean() == '}') {
                        return;
                    }
                    sourceTokenizer.back();
                    break;
                case '}':
                    return;
                default:
                    throw sourceTokenizer.syntaxError("Expected a ',' or '}'");
            }
        }
    }

    private void populateFromMap(JSONObject jo) {
        if (sourceMap == null)
            return;

        for (final Map.Entry<?, ?> e : sourceMap.entrySet()) {
            if (e.getKey() == null) {
                throw new NullPointerException("Null key.");
            }
            final Object value = e.getValue();
            if (value != null) {
                jo.put(String.valueOf(e.getKey()), JSONObject.wrap(value));
            }
        }
    }

    private void populateFromBeanWithObjectsRecord(JSONObject jo) {
        if (sourceBean == null)
            return;

        if(sourceBeanObjectsRecord == null)
            sourceBeanObjectsRecord = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

        Class<?> klass = sourceBean.getClass();

        // If klass is a System class then set includeSuperClass to false.

        boolean includeSuperClass = klass.getClassLoader() != null;

        Method[] methods = includeSuperClass ? klass.getMethods() : klass.getDeclaredMethods();
        for (final Method method : methods) {
            final int modifiers = method.getModifiers();
            if (Modifier.isPublic(modifiers)
                    && !Modifier.isStatic(modifiers)
                    && method.getParameterTypes().length == 0
                    && !method.isBridge()
                    && method.getReturnType() != Void.TYPE
                    && isValidMethodName(method.getName())) {
                final String key = getKeyNameFromMethod(method);
                if (key != null && !key.isEmpty()) {
                    try {
                        final Object result = method.invoke(sourceBean);
                        if (result != null) {
                            // check cyclic dependency and throw error if needed
                            // the wrap and populateMap combination method is
                            // itself DFS recursive
                            if (sourceBeanObjectsRecord.contains(result)) {
                                throw recursivelyDefinedObjectException(key);
                            }

                            sourceBeanObjectsRecord.add(result);

                            jo.put(key, wrap(result, sourceBeanObjectsRecord));

                            sourceBeanObjectsRecord.remove(result);

                            // we don't use the result anywhere outside of wrap
                            // if it's a resource we should be sure to close it
                            // after calling toString
                            if (result instanceof Closeable) {
                                try {
                                    ((Closeable) result).close();
                                } catch (IOException ignore) {
                                }
                            }
                        }
                    } catch (IllegalAccessException ignore) {
                    } catch (IllegalArgumentException ignore) {
                    } catch (InvocationTargetException ignore) {
                    }
                }
            }
        }
    }

    private static boolean isValidMethodName(String name) {
        return !"getClass".equals(name) && !"getDeclaringClass".equals(name);
    }

    private static String getKeyNameFromMethod(Method method) {
        final int ignoreDepth = getAnnotationDepth(method, JSONPropertyIgnore.class);
        if (ignoreDepth > 0) {
            final int forcedNameDepth = getAnnotationDepth(method, JSONPropertyName.class);
            if (forcedNameDepth < 0 || ignoreDepth <= forcedNameDepth) {
                // the hierarchy asked to ignore, and the nearest name override
                // was higher or non-existent
                return null;
            }
        }
        JSONPropertyName annotation = getAnnotation(method, JSONPropertyName.class);
        if (annotation != null && annotation.value() != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        String key;
        final String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            key = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2) {
            key = name.substring(2);
        } else {
            return null;
        }
        // if the first letter in the key is not uppercase, then skip.
        // This is to maintain backwards compatibility before PR406
        // (https://github.com/stleary/JSON-java/pull/406/)
        if (key.length() == 0 || Character.isLowerCase(key.charAt(0))) {
            return null;
        }
        if (key.length() == 1) {
            key = key.toLowerCase(Locale.ROOT);
        } else if (!Character.isUpperCase(key.charAt(1))) {
            key = key.substring(0, 1).toLowerCase(Locale.ROOT) + key.substring(1);
        }
        return key;
    }

    /**
     * Searches the class hierarchy to see if the method or it's super
     * implementations and interfaces has the annotation.
     *
     * @param <A>
     *            type of the annotation
     *
     * @param m
     *            method to check
     * @param annotationClass
     *            annotation to look for
     * @return the {@link Annotation} if the annotation exists on the current method
     *         or one of its super class definitions
     */
    private static <A extends Annotation> A getAnnotation(final Method m, final Class<A> annotationClass) {
        // if we have invalid data the result is null
        if (m == null || annotationClass == null) {
            return null;
        }

        if (m.isAnnotationPresent(annotationClass)) {
            return m.getAnnotation(annotationClass);
        }

        // if we've already reached the Object class, return null;
        Class<?> c = m.getDeclaringClass();
        if (c.getSuperclass() == null) {
            return null;
        }

        // check directly implemented interfaces for the method being checked
        for (Class<?> i : c.getInterfaces()) {
            try {
                Method im = i.getMethod(m.getName(), m.getParameterTypes());
                return getAnnotation(im, annotationClass);
            } catch (final SecurityException ex) {
                continue;
            } catch (final NoSuchMethodException ex) {
                continue;
            }
        }

        try {
            return getAnnotation(
                    c.getSuperclass().getMethod(m.getName(), m.getParameterTypes()),
                    annotationClass);
        } catch (final SecurityException ex) {
            return null;
        } catch (final NoSuchMethodException ex) {
            return null;
        }
    }

    /**
     * Searches the class hierarchy to see if the method or it's super
     * implementations and interfaces has the annotation. Returns the depth of the
     * annotation in the hierarchy.
     *
     * @param m
     *            method to check
     * @param annotationClass
     *            annotation to look for
     * @return Depth of the annotation or -1 if the annotation is not on the method.
     */
    private static int getAnnotationDepth(final Method m, final Class<? extends Annotation> annotationClass) {
        // if we have invalid data the result is -1
        if (m == null || annotationClass == null) {
            return -1;
        }

        if (m.isAnnotationPresent(annotationClass)) {
            return 1;
        }

        // if we've already reached the Object class, return -1;
        Class<?> c = m.getDeclaringClass();
        if (c.getSuperclass() == null) {
            return -1;
        }

        // check directly implemented interfaces for the method being checked
        for (Class<?> i : c.getInterfaces()) {
            try {
                Method im = i.getMethod(m.getName(), m.getParameterTypes());
                int d = getAnnotationDepth(im, annotationClass);
                if (d > 0) {
                    // since the annotation was on the interface, add 1
                    return d + 1;
                }
            } catch (final SecurityException ex) {
                continue;
            } catch (final NoSuchMethodException ex) {
                continue;
            }
        }

        try {
            int d = getAnnotationDepth(
                    c.getSuperclass().getMethod(m.getName(), m.getParameterTypes()),
                    annotationClass);
            if (d > 0) {
                // since the annotation was on the superclass, add 1
                return d + 1;
            }
            return -1;
        } catch (final SecurityException ex) {
            return -1;
        } catch (final NoSuchMethodException ex) {
            return -1;
        }
    }

    /**
     * Create a new JSONException in a common format for recursive object definition.
     * @param key name of the key
     * @return JSONException that can be thrown.
     */
    private static JSONException recursivelyDefinedObjectException(String key) {
        return new JSONException(
                "JavaBean object contains recursively defined member variable of key " + JSONObject.quote(key)
        );
    }

    private Object wrap(Object object, Set<Object> objectsRecord) {
        try {
            if (JSONObject.NULL.equals(object)) {
                return JSONObject.NULL;
            }
            if (object instanceof JSONObject || object instanceof JSONArray
                    || JSONObject.NULL.equals(object) || object instanceof JSONString
                    || object instanceof Byte || object instanceof Character
                    || object instanceof Short || object instanceof Integer
                    || object instanceof Long || object instanceof Boolean
                    || object instanceof Float || object instanceof Double
                    || object instanceof String || object instanceof BigInteger
                    || object instanceof BigDecimal || object instanceof Enum) {
                return object;
            }

            if (object instanceof Collection) {
                Collection<?> coll = (Collection<?>) object;
                return new JSONArray(coll);
            }
            if (object.getClass().isArray()) {
                return new JSONArray(object);
            }
            if (object instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) object;
                return new JSONObject(map);
            }
            Package objectPackage = object.getClass().getPackage();
            String objectPackageName = objectPackage != null ? objectPackage
                    .getName() : "";
            if (objectPackageName.startsWith("java.")
                    || objectPackageName.startsWith("javax.")
                    || object.getClass().getClassLoader() == null) {
                return object.toString();
            }
            if (objectsRecord != null) {
                return new JSONObjectBuilder()
                        .withMapFactory(mapFactory)
                        .withSourceBean(object, objectsRecord);
            }
            return new JSONObject(object);
        }
        catch (JSONException exception) {
            throw exception;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Construct a JSONObject from an Object, using reflection to find the
     * public members. The resulting JSONObject's keys will be the strings from
     * the names array, and the values will be the field values associated with
     * those keys in the object. If a key is not found or not visible, then it
     * will not be copied into the new JSONObject.
     *
     * @param object
     *            An object that has fields that should be used to make a
     *            JSONObject.
     * @param names
     *            An array of strings, the names of the fields to be obtained
     *            from the object.
     */
    public JSONObjectBuilder withSourceBean(Object object, String ... names) {
        if (sourceBean != null)
            throw new IllegalStateException("Only one bean source can be specified");
        this.sourceBean = object;
        this.sourceBeanNames = names;
        return this;
    }


    /**
     * Construct a JSONObject from an Object, using reflection to find the
     * public members. The resulting JSONObject's keys will be the strings from
     * the names array, and the values will be the field values associated with
     * those keys in the object. If a key is not found or not visible, then it
     * will not be copied into the new JSONObject.
     *
     * @param object
     *            An object that has fields that should be used to make a
     *            JSONObject.
     * @param names
     *            An array of strings, the names of the fields to be obtained
     *            from the object.
     */
    private void populateFromBeanWithNames(JSONObject object, String ... names) {
        if (sourceBean == null)
            return;

        Class<?> c = sourceBean.getClass();
        for (int i = 0; i < names.length; i += 1) {
            String name = names[i];
            try {
                object.putOpt(name, c.getField(name).get(sourceBean));
            } catch (Exception ignore) {
            }
        }
    }
}
