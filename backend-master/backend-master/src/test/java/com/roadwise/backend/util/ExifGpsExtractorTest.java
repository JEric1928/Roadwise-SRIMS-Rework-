package com.roadwise.backend.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds minimal in-memory images carrying crafted EXIF GPS blocks and
 * verifies {@link ExifGpsExtractor} recovers the fix (or refuses bad input).
 */
class ExifGpsExtractorTest {

    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
    private static final ByteOrder BE = ByteOrder.BIG_ENDIAN;

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    /** lat/lng as three {numerator, denominator} pairs each (degrees, minutes, seconds). */
    private static byte[] buildTiff(ByteOrder order, char latRef, long[][] latDms,
                                    char lngRef, long[][] lngDms) {
        int gpsIfdOffset = 8 + 2 + 12 + 4;          // header + IFD0
        int latDataOffset = gpsIfdOffset + 2 + 4 * 12 + 4;
        int lngDataOffset = latDataOffset + 24;
        ByteBuffer buf = ByteBuffer.allocate(lngDataOffset + 24).order(order);

        buf.put((byte) (order == LE ? 'I' : 'M'));
        buf.put((byte) (order == LE ? 'I' : 'M'));
        buf.putShort((short) 42);
        buf.putInt(8); // IFD0 offset

        // IFD0: single GPS-pointer entry.
        buf.putShort((short) 1);
        buf.putShort((short) 0x8825);
        buf.putShort((short) 4); // LONG
        buf.putInt(1);
        buf.putInt(gpsIfdOffset);
        buf.putInt(0); // next IFD

        // GPS IFD.
        buf.putShort((short) 4);
        putAsciiEntry(buf, 0x0001, latRef);
        buf.putShort((short) 0x0002);
        buf.putShort((short) 5); // RATIONAL
        buf.putInt(3);
        buf.putInt(latDataOffset);
        putAsciiEntry(buf, 0x0003, lngRef);
        buf.putShort((short) 0x0004);
        buf.putShort((short) 5); // RATIONAL
        buf.putInt(3);
        buf.putInt(lngDataOffset);
        buf.putInt(0); // next IFD

        putRationals(buf, latDms);
        putRationals(buf, lngDms);
        assertEquals(buf.capacity(), buf.position());
        return buf.array();
    }

    private static void putAsciiEntry(ByteBuffer buf, int tag, char ref) {
        buf.putShort((short) tag);
        buf.putShort((short) 2); // ASCII
        buf.putInt(2);
        // Inline ref bytes occupy the start of the value field either way.
        buf.put((byte) ref);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
    }

    private static void putRationals(ByteBuffer buf, long[][] dms) {
        for (long[] pair : dms) {
            buf.putInt((int) pair[0]);
            buf.putInt((int) pair[1]);
        }
    }

    private static byte[] exifPayload(byte[] tiff) {
        byte[] header = {'E', 'x', 'i', 'f', 0, 0};
        byte[] out = new byte[header.length + tiff.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(tiff, 0, out, header.length, tiff.length);
        return out;
    }

    private static byte[] buildJpeg(byte[] app1Payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8); // SOI
        if (app1Payload != null) {
            out.write(0xFF);
            out.write(0xE1); // APP1
            int len = app1Payload.length + 2;
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
            out.write(app1Payload, 0, app1Payload.length);
        }
        out.write(0xFF);
        out.write(0xD9); // EOI
        return out.toByteArray();
    }

    private static byte[] chunk(byte[] type, byte[] data, boolean bigEndianLength) {
        ByteBuffer buf = ByteBuffer.allocate(8 + data.length + 4);
        if (bigEndianLength) {
            buf.putInt(data.length);
        } else {
            buf.order(ByteOrder.LITTLE_ENDIAN).putInt(data.length);
        }
        buf.put(type);
        buf.put(data);
        buf.putInt(0xDEADBEEF); // CRC is not validated by the extractor
        return buf.array();
    }

    private static byte[] buildPngWithExif(byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] sig = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        out.write(sig, 0, sig.length);
        byte[] exif = chunk("eXIf".getBytes(StandardCharsets.US_ASCII), tiff, true);
        out.write(exif, 0, exif.length);
        byte[] iend = chunk("IEND".getBytes(StandardCharsets.US_ASCII), new byte[0], true);
        out.write(iend, 0, iend.length);
        return out.toByteArray();
    }

    private static byte[] buildWebPWithExif(byte[] exif) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII), 0, 4);
        int riffSizePos = out.size();
        out.write(new byte[4], 0, 4); // patched below
        out.write("WEBP".getBytes(StandardCharsets.US_ASCII), 0, 4);
        // WebP chunk order is FourCC, little-endian size, payload (no CRC).
        ByteBuffer chunk = ByteBuffer.allocate(8 + exif.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        chunk.put("EXIF".getBytes(StandardCharsets.US_ASCII));
        chunk.putInt(exif.length);
        chunk.put(exif);
        if ((exif.length & 1) == 1) {
            chunk.put((byte) 0); // even padding
        }
        byte[] exifChunk = new byte[chunk.position()];
        System.arraycopy(chunk.array(), 0, exifChunk, 0, exifChunk.length);
        out.write(exifChunk, 0, exifChunk.length);
        byte[] all = out.toByteArray();
        int riffSize = all.length - 8;
        ByteBuffer.wrap(all, riffSizePos, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(riffSize);
        return all;
    }

    // ------------------------------------------------------------------
    // Happy paths
    // ------------------------------------------------------------------

    @Test
    void jpegLittleEndianExtractsManilaFix() {
        // 14°35'12.345"N, 121°01'03"E
        byte[] tiff = buildTiff(LE, 'N', new long[][]{{14, 1}, {35, 1}, {12345, 1000}},
                'E', new long[][]{{121, 1}, {1, 1}, {3, 1}});
        Optional<ExifGpsExtractor.GpsCoordinates> gps =
                ExifGpsExtractor.extract(buildJpeg(exifPayload(tiff)));

        assertTrue(gps.isPresent());
        assertEquals(14 + 35 / 60.0 + 12.345 / 3600.0, gps.get().latitude(), 1e-9);
        assertEquals(121 + 1 / 60.0 + 3 / 3600.0, gps.get().longitude(), 1e-9);
    }

    @Test
    void jpegBigEndianExtractsSouthernWesternFix() {
        // 23°33'01"S, 46°38'02"W
        byte[] tiff = buildTiff(BE, 'S', new long[][]{{23, 1}, {33, 1}, {1, 1}},
                'W', new long[][]{{46, 1}, {38, 1}, {2, 1}});
        Optional<ExifGpsExtractor.GpsCoordinates> gps =
                ExifGpsExtractor.extract(buildJpeg(exifPayload(tiff)));

        assertTrue(gps.isPresent());
        assertEquals(-(23 + 33 / 60.0 + 1 / 3600.0), gps.get().latitude(), 1e-9);
        assertEquals(-(46 + 38 / 60.0 + 2 / 3600.0), gps.get().longitude(), 1e-9);
    }

    @Test
    void pngExifChunkExtracted() {
        byte[] tiff = buildTiff(LE, 'N', new long[][]{{14, 1}, {35, 1}, {12345, 1000}},
                'E', new long[][]{{121, 1}, {1, 1}, {3, 1}});
        Optional<ExifGpsExtractor.GpsCoordinates> gps =
                ExifGpsExtractor.extract(buildPngWithExif(tiff));

        assertTrue(gps.isPresent());
        assertEquals(14 + 35 / 60.0 + 12.345 / 3600.0, gps.get().latitude(), 1e-9);
    }

    @Test
    void webpExifChunkExtracted() {
        byte[] tiff = buildTiff(LE, 'N', new long[][]{{14, 1}, {35, 1}, {12345, 1000}},
                'E', new long[][]{{121, 1}, {1, 1}, {3, 1}});
        Optional<ExifGpsExtractor.GpsCoordinates> gps =
                ExifGpsExtractor.extract(buildWebPWithExif(exifPayload(tiff)));

        assertTrue(gps.isPresent());
        assertEquals(14 + 35 / 60.0 + 12.345 / 3600.0, gps.get().latitude(), 1e-9);
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    void jpegWithoutExifReturnsEmpty() {
        assertTrue(ExifGpsExtractor.extract(buildJpeg(null)).isEmpty());
    }

    @Test
    void tiffWithoutGpsIfdReturnsEmpty() {
        ByteBuffer buf = ByteBuffer.allocate(8 + 2 + 4).order(LE);
        buf.put((byte) 'I');
        buf.put((byte) 'I');
        buf.putShort((short) 42);
        buf.putInt(8);
        buf.putShort((short) 0); // zero IFD0 entries
        buf.putInt(0);
        assertTrue(ExifGpsExtractor.extract(buildJpeg(exifPayload(buf.array()))).isEmpty());
    }

    @Test
    void zeroFixIsRejected() {
        byte[] tiff = buildTiff(LE, 'N', new long[][]{{0, 1}, {0, 1}, {0, 1}},
                'E', new long[][]{{0, 1}, {0, 1}, {0, 1}});
        assertTrue(ExifGpsExtractor.extract(buildJpeg(exifPayload(tiff))).isEmpty());
    }

    @Test
    void outOfRangeFixIsRejected() {
        byte[] tiff = buildTiff(LE, 'N', new long[][]{{95, 1}, {0, 1}, {0, 1}},
                'E', new long[][]{{121, 1}, {1, 1}, {3, 1}});
        assertTrue(ExifGpsExtractor.extract(buildJpeg(exifPayload(tiff))).isEmpty());
    }

    @Test
    void zeroDenominatorIsRejected() {
        byte[] tiff = buildTiff(LE, 'N', new long[][]{{14, 1}, {35, 0}, {12345, 1000}},
                'E', new long[][]{{121, 1}, {1, 1}, {3, 1}});
        assertTrue(ExifGpsExtractor.extract(buildJpeg(exifPayload(tiff))).isEmpty());
    }

    @Test
    void garbageAndTruncatedInputsReturnEmpty() {
        assertTrue(ExifGpsExtractor.extract(null).isEmpty());
        assertTrue(ExifGpsExtractor.extract(new byte[0]).isEmpty());
        assertTrue(ExifGpsExtractor.extract(new byte[20]).isEmpty());
        byte[] text = "this is definitely not an image file....".getBytes(StandardCharsets.US_ASCII);
        assertTrue(ExifGpsExtractor.extract(text).isEmpty());

        // APP1 length overruns the buffer.
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x05, 0x00, 'E', 'x'};
        assertTrue(ExifGpsExtractor.extract(truncated).isEmpty());

        // "Exif" header present but TIFF cut short (only byte-order mark).
        byte[] shortTiff = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1,
                0x00, 0x0A, 'E', 'x', 'i', 'f', 0, 0, 'I', 'I'};
        assertTrue(ExifGpsExtractor.extract(shortTiff).isEmpty());
    }
}
