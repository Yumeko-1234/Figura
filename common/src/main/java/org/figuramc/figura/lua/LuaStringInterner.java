package org.figuramc.figura.lua;

import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

// one shared LuaString instance per short string, for script constants and the keys of API tables
// luaj compares table keys by identity first and only then byte by byte: the "getPos" constant of a script and the "getPos" key
// of the API index table were separate instances, so every method or global lookup ended in a byte compare (~1.3% of a crowded frame)
// equal content means equal Lua value, so swapping an instance for its pooled twin changes nothing a script can observe
public final class LuaStringInterner {

    // identifiers and short literals, long strings (text, json) are rarely table keys
    private static final int MAX_LENGTH = 48;
    // avatars stay loaded for the whole session, this bounds the pool if many of them come and go
    private static final int MAX_ENTRIES = 1 << 16;

    private static final ConcurrentHashMap<LuaString, LuaString> POOL = new ConcurrentHashMap<>();

    // luaj looks metamethods up with its own constants, so those are the instances to share
    static {
        for (LuaString s : new LuaString[]{LuaValue.INDEX, LuaValue.NEWINDEX, LuaValue.CALL, LuaValue.MODE, LuaValue.METATABLE,
                LuaValue.ADD, LuaValue.SUB, LuaValue.DIV, LuaValue.MUL, LuaValue.POW, LuaValue.MOD, LuaValue.UNM, LuaValue.LEN,
                LuaValue.EQ, LuaValue.LT, LuaValue.LE, LuaValue.TOSTRING, LuaValue.CONCAT})
            POOL.put(s, s);
    }

    private LuaStringInterner() {}

    public static LuaString intern(LuaString s) {
        if (s.m_length > MAX_LENGTH)
            return s;
        LuaString pooled = POOL.get(s);
        if (pooled != null)
            return pooled;
        if (POOL.size() >= MAX_ENTRIES)
            return s;
        // a string sharing a bigger backing array would keep all of it alive, pool a compact copy instead
        if (s.m_offset != 0 || s.m_bytes.length != s.m_length) {
            byte[] copy = new byte[s.m_length];
            System.arraycopy(s.m_bytes, s.m_offset, copy, 0, s.m_length);
            s = LuaString.valueUsing(copy);
        }
        pooled = POOL.putIfAbsent(s, s);
        return pooled == null ? s : pooled;
    }

    public static LuaString intern(String s) {
        return intern(LuaString.valueOf(s));
    }

    // the constants of a freshly compiled chunk and all its nested functions
    public static void internConstants(Prototype p) {
        if (p == null)
            return;
        LuaValue[] k = p.k;
        if (k != null)
            for (int i = 0; i < k.length; i++)
                if (k[i] instanceof LuaString s)
                    k[i] = intern(s);
        if (p.p != null)
            for (Prototype child : p.p)
                internConstants(child);
    }

    // re-keys the string keys of a table with pooled instances (a plain set keeps the old key instance, so remove and add again)
    public static void internKeys(LuaTable table) {
        List<LuaString> keys = new ArrayList<>();
        LuaValue k = LuaValue.NIL;
        while (true) {
            k = table.next(k).arg1();
            if (k.isnil())
                break;
            if (k instanceof LuaString s && s.m_length <= MAX_LENGTH && intern(s) != s)
                keys.add(s);
        }
        for (LuaString key : keys) {
            LuaValue value = table.rawget(key);
            table.rawset(key, LuaValue.NIL);
            table.rawset(intern(key), value);
        }
    }

    // the globals and the library tables directly under them (math, string, table, vectors...)
    public static void internGlobals(LuaTable globals) {
        internKeys(globals);
        LuaValue k = LuaValue.NIL;
        while (true) {
            org.luaj.vm2.Varargs entry = globals.next(k);
            k = entry.arg1();
            if (k.isnil())
                break;
            if (entry.arg(2) instanceof LuaTable t && t != globals)
                internKeys(t);
        }
    }
}
