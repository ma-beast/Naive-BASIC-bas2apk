package com.naivework.basapkmaker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Changes package name and application label in compiled binary AndroidManifest.xml.
 * Works with both historical templates that store android:label as a literal string
 * and newer Android Gradle templates where android:label is a @string resource.
 */
public final class BinaryXmlLabel {
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int TYPE_STRING = 0x03;
    private static final int NO_INDEX = 0xFFFFFFFF;

    private BinaryXmlLabel() {}

    public static byte[] setApplicationLabelAndPackage(byte[] xml, String label, String packageName) throws Exception {
        if (xml == null || xml.length < 28) throw new IOException("Некорректный AndroidManifest.xml");
        if (label == null) label = "";
        if (packageName == null || packageName.length() == 0) throw new IOException("Пустое имя пакета");
        if (u16(xml, 0) != RES_XML_TYPE) throw new IOException("Не бинарный AndroidManifest.xml");

        final int stringPoolOffset = 8;
        if (u16(xml, stringPoolOffset) != RES_STRING_POOL_TYPE)
            throw new IOException("Неожиданный AndroidManifest.xml: string pool не найден");

        int spHeader = u16(xml, stringPoolOffset + 2);
        int spSize = i32(xml, stringPoolOffset + 4);
        int stringCount = i32(xml, stringPoolOffset + 8);
        int styleCount = i32(xml, stringPoolOffset + 12);
        int flags = i32(xml, stringPoolOffset + 16);
        int stringsStart = i32(xml, stringPoolOffset + 20);

        if (spHeader < 28 || spSize < spHeader || stringPoolOffset + spSize > xml.length)
            throw new IOException("Неожиданный string pool AndroidManifest.xml");
        if (stringCount <= 0 || stringCount > 10000) throw new IOException("Некорректный string pool");

        int[] offsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++)
            offsets[i] = i32(xml, stringPoolOffset + spHeader + i * 4);

        boolean utf8 = (flags & 0x00000100) != 0;
        String[] strings = new String[stringCount];
        for (int i = 0; i < stringCount; i++)
            strings[i] = readString(xml, stringPoolOffset + stringsStart + offsets[i], utf8);

        int packageIndex = find(strings, "com.naivework");
        if (packageIndex < 0) throw new IOException("Имя пакета com.naivework не найдено в шаблоне NaiveWORK");
        strings[packageIndex] = packageName;

        // Keep all old indices stable and append the generated app label as a new string.
        // This is important because newer NaiveWORK uses @string/app_name in the manifest,
        // so there may be no literal "Naive WORK" string in the manifest pool at all.
        int labelIndex = strings.length;
        String[] newStrings = Arrays.copyOf(strings, strings.length + 1);
        newStrings[labelIndex] = label;

        byte[] newPool = buildStringPool(newStrings, flags, styleCount);
        int restStart = stringPoolOffset + spSize;
        byte[] result = new byte[8 + newPool.length + (xml.length - restStart)];
        System.arraycopy(xml, 0, result, 0, 8);
        System.arraycopy(newPool, 0, result, 8, newPool.length);
        System.arraycopy(xml, restStart, result, 8 + newPool.length, xml.length - restStart);
        put32(result, 4, result.length);

        // The label attribute can be a resource reference in NaiveWORK 0.2G.
        // Patch the <application android:label=...> attribute itself to a TYPE_STRING.
        patchApplicationLabel(result, newStrings, 8 + newPool.length, labelIndex);
        return result;
    }

    private static void patchApplicationLabel(byte[] xml, String[] strings, int firstChunk, int labelIndex) throws Exception {
        int p = firstChunk;
        while (p + 8 <= xml.length) {
            int type = u16(xml, p);
            int headerSize = u16(xml, p + 2);
            int size = i32(xml, p + 4);
            if (size < 8 || p + size > xml.length) throw new IOException("Повреждён AndroidManifest.xml");

            if (type == RES_XML_START_ELEMENT_TYPE) {
                if (headerSize < 16 || p + 36 > xml.length) throw new IOException("Повреждён START_ELEMENT");
                int elementNameIndex = i32(xml, p + 20);
                String elementName = stringAt(strings, elementNameIndex);
                if ("application".equals(elementName)) {
                    int attributeStart = u16(xml, p + 24);
                    int attributeSize = u16(xml, p + 26);
                    int attributeCount = u16(xml, p + 28);
                    if (attributeSize < 20) throw new IOException("Неожиданный размер атрибута AndroidManifest.xml");
                    int a = p + 16 + attributeStart;
                    for (int i = 0; i < attributeCount; i++, a += attributeSize) {
                        if (a + 20 > p + size) throw new IOException("Повреждён атрибут AndroidManifest.xml");
                        int nameIndex = i32(xml, a + 4);
                        if ("label".equals(stringAt(strings, nameIndex))) {
                            put32(xml, a + 8, labelIndex); // rawValue string index
                            put16(xml, a + 12, 8);         // Res_value.size
                            xml[a + 14] = 0;               // res0
                            xml[a + 15] = (byte) TYPE_STRING;
                            put32(xml, a + 16, labelIndex); // typed string index
                            return;
                        }
                    }
                    throw new IOException("Атрибут android:label не найден в шаблоне NaiveWORK");
                }
            }
            p += size;
        }
        throw new IOException("Элемент application не найден в AndroidManifest.xml");
    }

    private static int find(String[] strings, String target) {
        for (int i = 0; i < strings.length; i++) if (target.equals(strings[i])) return i;
        return -1;
    }

    private static String stringAt(String[] strings, int index) {
        if (index < 0 || index >= strings.length) return null;
        return strings[index];
    }

    private static byte[] buildStringPool(String[] strings, int flags, int styleCount) throws Exception {
        boolean utf8 = (flags & 0x00000100) != 0;
        if (styleCount != 0) throw new IOException("String pool со стилями не поддерживается");

        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            data.write(encodeString(strings[i], utf8));
        }
        while ((data.size() & 3) != 0) data.write(0);

        int headerSize = 28;
        int stringsStart = headerSize + strings.length * 4;
        int newSize = stringsStart + data.size();
        byte[] pool = new byte[newSize];
        put16(pool, 0, RES_STRING_POOL_TYPE);
        put16(pool, 2, headerSize);
        put32(pool, 4, newSize);
        put32(pool, 8, strings.length);
        put32(pool, 12, 0);
        put32(pool, 16, flags);
        put32(pool, 20, stringsStart);
        put32(pool, 24, 0);
        for (int i = 0; i < offsets.length; i++) put32(pool, headerSize + i * 4, offsets[i]);
        byte[] db = data.toByteArray();
        System.arraycopy(db, 0, pool, stringsStart, db.length);
        return pool;
    }

    private static byte[] encodeString(String s, boolean utf8) throws Exception {
        if (utf8) {
            byte[] chars = s.getBytes("UTF-8");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            writeUtf8Length(b, s.length());
            writeUtf8Length(b, chars.length);
            b.write(chars);
            b.write(0);
            return b.toByteArray();
        }
        byte[] chars = s.getBytes("UTF-16LE");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeUtf16Length(b, chars.length / 2);
        b.write(chars);
        b.write(0);
        b.write(0);
        return b.toByteArray();
    }

    private static void writeUtf8Length(ByteArrayOutputStream b, int n) {
        if (n > 0x7F) {
            b.write(((n >> 7) & 0x7F) | 0x80);
            b.write(n & 0x7F);
        } else b.write(n);
    }

    private static void writeUtf16Length(ByteArrayOutputStream b, int n) {
        if (n > 0x7FFF) {
            int hi = ((n >> 16) & 0x7FFF) | 0x8000;
            b.write(hi & 255); b.write((hi >>> 8) & 255);
            b.write(n & 255); b.write((n >>> 8) & 255);
        } else {
            b.write(n & 255); b.write((n >>> 8) & 255);
        }
    }

    private static String readString(byte[] a, int p, boolean utf8) throws Exception {
        if (utf8) {
            int[] r1 = readUtf8Length(a, p); p = r1[1];
            int[] r2 = readUtf8Length(a, p); p = r2[1];
            return new String(a, p, r2[0], "UTF-8");
        }
        int[] r = readUtf16Length(a, p);
        int n = r[0]; p = r[1];
        return new String(a, p, n * 2, "UTF-16LE");
    }

    private static int[] readUtf8Length(byte[] a, int p) {
        int b = a[p++] & 255;
        if ((b & 0x80) == 0) return new int[]{b, p};
        int b2 = a[p++] & 255;
        return new int[]{((b & 0x7F) << 7) | b2, p};
    }

    private static int[] readUtf16Length(byte[] a, int p) {
        int first = u16(a, p); p += 2;
        if ((first & 0x8000) == 0) return new int[]{first, p};
        int second = u16(a, p); p += 2;
        return new int[]{((first & 0x7FFF) << 16) | second, p};
    }

    private static int u16(byte[] a, int p) { return (a[p] & 255) | ((a[p + 1] & 255) << 8); }
    private static int i32(byte[] a, int p) {
        return (a[p] & 255) | ((a[p + 1] & 255) << 8) | ((a[p + 2] & 255) << 16) | ((a[p + 3] & 255) << 24);
    }
    private static void put16(byte[] a, int p, int v) { a[p]=(byte)v; a[p+1]=(byte)(v>>>8); }
    private static void put32(byte[] a, int p, int v) {
        a[p]=(byte)v; a[p+1]=(byte)(v>>>8); a[p+2]=(byte)(v>>>16); a[p+3]=(byte)(v>>>24);
    }
}
