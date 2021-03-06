package com.x5.util;

import com.madrobot.beans.BeanInfo;
import com.madrobot.beans.Introspector;
import com.madrobot.beans.PropertyDescriptor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * ObjectDataMap
 * <p/>
 * Box POJO/Bean/DataCapsule inside a Map.  When accessed, pry into object
 * using reflection/introspection/capsule-export and pull out all public
 * member fields/properties.
 * Convert field names from camelCase to lower_case_with_underscores
 * Convert bean properties from getSomeProperty() to some_property
 * or isVeryHappy() to is_very_happy
 * <p/>
 * Values returned are copies, frozen at time of first access.
 */
@SuppressWarnings("rawtypes")
public class ObjectDataMap implements Map {
    private Map<String, Object> pickle = null;
    private Object object;
    private boolean isBean = false;

    private static final Map<String, Object> EMPTY_MAP = new HashMap<String, Object>();

    private static final HashSet<Class<?>> WRAPPER_TYPES = getWrapperTypes();

    private static HashSet<Class<?>> getWrapperTypes() {
        HashSet<Class<?>> ret = new HashSet<Class<?>>();
        ret.add(Boolean.class);
        ret.add(Character.class);
        ret.add(Byte.class);
        ret.add(Short.class);
        ret.add(Integer.class);
        ret.add(Long.class);
        ret.add(Float.class);
        ret.add(Double.class);
        ret.add(Void.class);
        return ret;
    }

    public static boolean isWrapperType(Class<?> clazz) {
        return WRAPPER_TYPES.contains(clazz);
    }

    public ObjectDataMap(Object pojo) {
        this.object = pojo;
    }

    private void init() {
        if (pickle == null) {
            pickle = mapify(object);
            // prevent multiple expensive calls to mapify
            // when result is null
            if (pickle == null) {
                pickle = EMPTY_MAP;
            }
        }
    }

    public static ObjectDataMap wrapBean(Object bean) {
        if (bean == null) return null;
        ObjectDataMap boxedBean = new ObjectDataMap(bean);
        boxedBean.isBean = true;

        return boxedBean;
    }

    private static final Class[] NO_ARGS = new Class[]{};

    public static String getAsString(Object obj) {
        Method toString = null;
        try {
            toString = obj.getClass().getMethod("toString", NO_ARGS);
        } catch (NoSuchMethodException e) {
        } catch (SecurityException e) {
        }

        // If this class has its own toString() method, use it.
        if (toString.getDeclaringClass().equals(Object.class)) {
            // don't expose the default toString() info.
            return "OBJECT:" + obj.getClass().getName();
        } else {
            return obj.toString();
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> mapify(Object pojo) {
        Map<String, Object> data = null;
        if (pojo instanceof DataCapsule) {
            return mapifyCapsule((DataCapsule) pojo);
        }
        if (!isBean) {
            data = mapifyPOJO(pojo);
            if (data == null || data.isEmpty()) {
                // hmmm, maybe it's a bean?
                isBean = true;
            } else {
                return data;
            }
        }
        if (isBean) {
            try {
                // java.beans.* is missing on android.
                // Test for existence before use...
                try {
                    Class<?> beanClass = Class.forName("java.beans.Introspector");
                    return StandardIntrospector.mapifyBean(pojo);
                } catch (ClassNotFoundException e) {
                    try {
                        Class<?> madrobotClass = Class.forName("com.madrobot.beans.Introspector");
                        return MadRobotIntrospector.mapifyBean(pojo);
                    } catch (ClassNotFoundException e2) {
                        // soldier on, treat as pojo
                    }
                }
            } catch (Exception e) {
                // hmm, not a bean after all...
            }
        }
        return data;
    }

    public Map<String, Object> mapifyPOJO(Object pojo) {
        Field[] fields = pojo.getClass().getDeclaredFields();
        Map<String, Object> pickle = null;

        for (int i = 0; i < fields.length; i++) {
            Field field = fields[i];
            String paramName = field.getName();
            Class paramClass = field.getType();

            // force access
            int mods = field.getModifiers();
            if (!Modifier.isPrivate(mods) && !Modifier.isProtected(mods)) {
                field.setAccessible(true);
            }

            Object paramValue = null;
            try {
                paramValue = field.get(pojo);
            } catch (IllegalAccessException e) {
                continue;
            }

            if (pickle == null) pickle = new HashMap<String, Object>();
            // convert isActive to is_active
            paramName = splitCamelCase(paramName);
            storeValue(pickle, paramClass, paramName, paramValue, isBean);
        }

        return pickle;
    }

    private Map<String, Object> mapifyCapsule(DataCapsule capsule) {
        DataCapsuleReader reader = DataCapsuleReader.getReader(capsule);

        String[] tags = reader.getColumnLabels(null);
        Object[] data = reader.extractData(capsule);

        pickle = new HashMap<String, Object>();
        for (int i = 0; i < tags.length; i++) {
            Object val = data[i];
            if (val == null) continue;
            if (val instanceof String) {
                pickle.put(tags[i], val);
            } else if (val instanceof DataCapsule) {
                pickle.put(tags[i], new ObjectDataMap(val));
            } else {
                pickle.put(tags[i], val.toString());
            }
        }

        return pickle;
    }

    private static void storeValue(Map<String, Object> pickle, Class paramClass,
                                   String paramName, Object paramValue, boolean isBean) {
        if (paramValue == null) {
            pickle.put(paramName, null);
        } else if (paramClass.isArray() || paramValue instanceof List) {
            pickle.put(paramName, paramValue);
        } else if (paramClass == String.class) {
            pickle.put(paramName, paramValue);
        } else if (paramValue instanceof Boolean) {
            if (((Boolean) paramValue).booleanValue()) {
                pickle.put(paramName, "TRUE");
            }
        } else if (paramClass.isPrimitive() || isWrapperType(paramClass)) {
            pickle.put(paramName, paramValue.toString());
        } else {
            // box all non-primitive object member fields
            // in their own ObjectDataMap wrapper.
            // lazy init guarantees no infinite recursion here.
            ObjectDataMap boxedParam = isBean ? wrapBean(paramValue) : new ObjectDataMap(paramValue);
            pickle.put(paramName, boxedParam);
        }

    }

    // splitCamelCase converts SimpleXMLStuff to Simple_XML_Stuff
    public static String splitCamelCase(String s) {
        return s.replaceAll(
                String.format("%s|%s|%s",
                        "(?<=[A-Z])(?=[A-Z][a-z])",
                        "(?<=[^A-Z])(?=[A-Z])",
                        "(?<=[A-Za-z])(?=[^A-Za-z])"
                ),
                "_"
        ).toLowerCase();
    }

    public int size() {
        init();
        return pickle.size();
    }

    public boolean isEmpty() {
        init();
        return pickle.isEmpty();
    }

    public boolean containsKey(Object key) {
        init();
        return pickle.containsKey(key);
    }

    public boolean containsValue(Object value) {
        init();
        return pickle.containsValue(value);
    }

    public Object get(Object key) {
        init();
        return pickle.get(key);
    }

    public Object put(Object key, Object value) {
        // unsupported
        return null;
    }

    public Object remove(Object key) {
        // unsupported
        return null;
    }

    public void putAll(Map m) {
        // unsupported
    }

    public void clear() {
        // unsupported
    }

    public Set keySet() {
        init();
        return pickle.keySet();
    }

    public Collection values() {
        init();
        return pickle.values();
    }

    public Set entrySet() {
        init();
        return pickle.entrySet();
    }

    private static class IntrospectionException extends Exception {
        private static final long serialVersionUID = 8890979383599687484L;
    }

    private static class StandardIntrospector {
        private static Map<String, Object> mapifyBean(Object bean)
                throws Exception {
            PropertyDescriptor[] properties = null;
            try {
                BeanInfo beanInfo = Introspector.getBeanInfo(bean.getClass());
                properties = beanInfo.getPropertyDescriptors();
            } catch (com.madrobot.beans.IntrospectionException e) {
                throw new Exception(e);
            }

            if (properties == null) return null;

            Map<String, Object> pickle = null;

            // copy properties into hashtable
            for (PropertyDescriptor property : properties) {
                Class paramClass = property.getPropertyType();
                Method getter = property.getReadMethod();
                try {
                    Object paramValue = getter.invoke(bean, (Object[]) null);

                    if (paramValue != null) {
                        // converts isActive() to is_active
                        // converts getBookTitle() to book_title
                        String paramName = property.getName();
                        paramName = splitCamelCase(paramName);
                        if (paramValue instanceof Boolean) {
                            paramName = "is_" + paramName;
                        }

                        if (pickle == null) pickle = new HashMap<String, Object>();

                        storeValue(pickle, paramClass, paramName, paramValue, true);
                    }
                } catch (InvocationTargetException e) {
                } catch (IllegalAccessException e) {
                }
            }

            return pickle;
        }
    }

    // mad robot provides a stopgap introspection library for android projects
    private static class MadRobotIntrospector {
        private static Map<String, Object> mapifyBean(Object bean)
                throws Exception {
            com.madrobot.beans.PropertyDescriptor[] properties = null;
            try {
                com.madrobot.beans.BeanInfo beanInfo = com.madrobot.beans.Introspector.getBeanInfo(bean.getClass());
                properties = beanInfo.getPropertyDescriptors();
            } catch (com.madrobot.beans.IntrospectionException e) {
                throw new Exception(e);
            }

            if (properties == null) return null;

            Map<String, Object> pickle = null;

            // copy properties into hashtable
            for (com.madrobot.beans.PropertyDescriptor property : properties) {
                Class paramClass = property.getPropertyType();
                Method getter = property.getReadMethod();
                try {
                    Object paramValue = getter.invoke(bean, (Object[]) null);

                    if (paramValue != null) {
                        // converts isActive() to is_active
                        // converts getBookTitle() to book_title
                        String paramName = property.getName();
                        paramName = splitCamelCase(paramName);
                        if (paramValue instanceof Boolean) {
                            paramName = "is_" + paramName;
                        }

                        if (pickle == null) pickle = new HashMap<String, Object>();

                        storeValue(pickle, paramClass, paramName, paramValue, true);
                    }
                } catch (InvocationTargetException e) {
                } catch (IllegalAccessException e) {
                }
            }

            return pickle;
        }
    }

    public String toString() {
        return getAsString(this.object);
    }

    public Object unwrap() {
        return this.object;
    }
}
