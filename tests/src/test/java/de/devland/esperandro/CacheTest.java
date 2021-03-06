package de.devland.esperandro;

import android.content.SharedPreferences;
import android.util.LruCache;
import de.devland.esperandro.tests.EsperandroCacheExample;
import de.devland.esperandro.tests.EsperandroCacheOnPutExample;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Created by deekay on 20.12.2015.
 */
@Config(manifest = Config.NONE)
@RunWith(RobolectricTestRunner.class)
public class CacheTest {

    private EsperandroCacheExample cachePreferences;
    private EsperandroCacheOnPutExample cacheOnPutPreferences;

    @Before
    public void setup() {
        cachePreferences = Esperandro.getPreferences(EsperandroCacheExample.class, RuntimeEnvironment.application);
        cacheOnPutPreferences = Esperandro.getPreferences(EsperandroCacheOnPutExample.class, RuntimeEnvironment.application);
    }

    @After
    public void tearDown() {
        cachePreferences.clear();
        cacheOnPutPreferences.clear();
    }

    @Test
    public void cacheGet() {
        LruCache<String, Object> cache = getCache(cachePreferences);
        SharedPreferences prefs = cachePreferences.get();
        prefs.edit().putString("cachedValue", "value").apply();
        assertNull(cache.get("cachedValue"));
        // after get, value should be in cache
        assertEquals("value", cachePreferences.cachedValue());
        assertEquals("value", cache.get("cachedValue"));
        prefs.edit().putString("cachedValue", "newValue").apply();
        // value should be the same since it's cached and value from cache should be returned
        assertEquals("value", cache.get("cachedValue"));

    }

    @Test
    public void cachePutEvict() {
        LruCache<String, Object> cache = getCache(cachePreferences);
        SharedPreferences prefs = cachePreferences.get();
        prefs.edit().putString("cachedValue", "value").apply();
        assertNull(cache.get("cachedValue"));
        // after get, value should be in cache
        assertEquals("value", cachePreferences.cachedValue());
        assertEquals("value", cache.get("cachedValue"));
        cachePreferences.cachedValue("newValue");
        assertNull(cache.get("cachedValue"));
    }

    @Test
    public void cacheSize() {
        LruCache<String, Object> autoSizedCache = getCache(cachePreferences);
        LruCache<String, Object> fixedSizedCache = getCache(cacheOnPutPreferences);

        Assert.assertEquals(4, autoSizedCache.maxSize());
        Assert.assertEquals(30, fixedSizedCache.maxSize());
    }

    @Test
    public void cachePutUpdate() {
        LruCache<String, Object> cache = getCache(cacheOnPutPreferences);
        SharedPreferences prefs = cacheOnPutPreferences.get();
        prefs.edit().putString("cachedValue", "value").apply();
        assertNull(cache.get("cachedValue"));
        // after get, value should be in cache
        assertEquals("value", cacheOnPutPreferences.cachedValue());
        assertEquals("value", cache.get("cachedValue"));
        cacheOnPutPreferences.cachedValue("newValue");
        assertEquals("newValue", cache.get("cachedValue"));
    }

    @Test
    public void clearAll() {
        cacheOnPutPreferences.cachedValue("value");
        cacheOnPutPreferences.primitive(42);
        LruCache<String, Object> cache = getCache(cacheOnPutPreferences);
        assertEquals(2, cache.size());
        cacheOnPutPreferences.clear();
        assertEquals(0, cache.size());
    }

    @Test
    public void clearDefined() {
        cacheOnPutPreferences.cachedValue("value");
        cacheOnPutPreferences.primitive(42);
        LruCache<String, Object> cache = getCache(cacheOnPutPreferences);
        assertEquals(2, cache.size());
        cacheOnPutPreferences.clearDefined();
        assertEquals(0, cache.size());
    }

    @Test
    public void remove() {
        cacheOnPutPreferences.cachedValue("value");
        cacheOnPutPreferences.primitive(42);
        LruCache<String, Object> cache = getCache(cacheOnPutPreferences);
        assertEquals(2, cache.size());
        cacheOnPutPreferences.remove("primitive");
        assertEquals(1, cache.size());
        cacheOnPutPreferences.remove("cachedValue");
        assertEquals(0, cache.size());
    }

    @Test
    public void resetCache() {
        LruCache<String, Object> cache = getCache(cachePreferences);
        SharedPreferences prefs = cachePreferences.get();
        prefs.edit().putString("cachedValue", "foo").apply();
        cachePreferences.cachedValue();
        Assert.assertEquals("foo", cache.get("cachedValue"));
        prefs.edit().putString("cachedValue", "bar").apply();
        Assert.assertEquals("foo", cache.get("cachedValue"));
        cachePreferences.resetCache();
        Assert.assertNull(cache.get("cachedValue"));
    }

    @Test
    public void putNull() {
        // no exception should be thrown
        cacheOnPutPreferences.cachedValue(null);

        cachePreferences.cachedValue(null);
        cachePreferences.cachedValue();
    }

    private LruCache<String, Object> getCache(SharedPreferenceActions preferences) {
        try {
            Field cacheField = preferences.getClass().getDeclaredField("cache");
            cacheField.setAccessible(true);
            return (LruCache<String, Object>) cacheField.get(preferences);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }
        return null;
    }
}
