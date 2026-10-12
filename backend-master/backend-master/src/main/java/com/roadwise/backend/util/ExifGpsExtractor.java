package com.roadwise.backend.util;

import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Extracts GPS coordinates from image EXIF metadata (automatic geotagging).
 *
 * <p>Phone cameras typically embed the capture location in the photo's EXIF
 * {@code GPS} IFD. When a report is submitted without map coordinates, the
 * controller backfills latitude/longitude from these tags so the report can
 * still be pinned on the map.</p>
 *
 * <p>Supported containers: JPEG ({@code APP1/Exif}), PNG ({@code eXIf} chunk)
 * and WebP ({@code EXIF} chunk). The parser is strict and defensive: any
 * malformed, truncated or out-of-range input yields {@link Optional#empty()}
 * instead of throwing, and offsets are bounds-checked before every read so a
 * hostile file can never cause an out-of-bounds access or a large allocation.
 * No third-party imaging library is required.</p>
 */
public final class ExifGpsExtractor {

    /** Decimal-degree coordinates recovered from EXIF GPS tags. */
    public record GpsCoordinates(double latitude, double longitude) {
    }

    private static final int MAX_SEGMENTS = 128;

    // EXIF field types we interpret.
    private static final int TYPE_ASCII = 2;
    private static final int TYPE_LONG = 4;
    private static final int TYPE_RATIONAL = 5;
    private static final int TYPE_SRATIONAL = 10;

    // GPS IFD tags.
    private static final int TAG_LAT_REF = 0x0001;
    private static final int TAG_LATITUDE = 0x0002;
    private static final int TAG_LNG_REF = 0x0003;
    private static final int TAG_LONGITUDE = 0x0004;
    private static final int TAG_GPS_IFD_POINTER = 0x8825;

    private ExifGpsExtractor() {
    }

    /**
     * @param imageBytes raw bytes of an uploaded image.
     * @return the EXIF GPS fix, or empty when the image carries no usable
     *         coordinates (no EXIF, no GPS tags, zero fix, out-of-range fix).
     */
    public static Optional<GpsCoordinates> extract(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length < 16) {
            return Optional.empty();
        }
        try {
            if (isJpeg(imageBytes)) {
                return extractFromJpeg(imageBytes);
            }
            if (isPng(imageBytes)) {
                return extractFromPng(imageBytes);
            }
            if (isWebP(imageBytes)) {
                return extractFromWebP(imageBytes);
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // Container scanners
    // ------------------------------------------------------------------

    private static Optional<GpsCoordinates> extractFromJpeg(byte[] img) {
        int pos = 2; // skip SOI
        int segments = 0;
        while (pos + 1 < img.length && segments++ < MAX_SEGMENTS) {
            if ((img[pos] & 0xFF) != 0xFF) {
                return Optional.empty(); // entered entropy-coded data
            }
            int marker = img[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // fill byte, re-examine
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return Optional.empty(); // EOI / SOS: no metadata beyond here
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                pos += 2; // standalone markers carry no length
                continue;
            }
            if (pos + 3 >= img.length) {
                return Optional.empty();
            }
            int len = u16(img, pos + 2, ByteOrder.BIG_ENDIAN);
            if (len < 2 || (long) pos + 2 + len > img.length) {
                return Optional.empty();
            }
            if (marker == 0xE1 && len >= 8 && isExifHeader(img, pos + 4)) {
                Optional<GpsCoordinates> gps = parseTiff(img, pos + 10, pos + 2 + len);
                if (gps.isPresent()) {
                    return gps;
                }
                // Another APP1 segment (e.g. XMP) may still hold EXIF; keep scanning.
            }
            pos += 2 + len;
        }
        return Optional.empty();
    }

    private static Optional<GpsCoordinates> extractFromPng(byte[] img) {
        int pos = 8; // skip signature
        int chunks = 0;
        while (pos + 8 <= img.length && chunks++ < MAX_SEGMENTS) {
            long chunkLen = u32(img, pos, ByteOrder.BIG_ENDIAN);
            int typePos = pos + 4;
            long dataPos = (long) typePos + 4;
            if (chunkLen < 0 || dataPos + chunkLen + 4 > img.length) {
                return Optional.empty();
            }
            if (isChunkType(img, typePos, 'e', 'X', 'I', 'f')) {
                return parseTiff(img, (int) dataPos, (int) (dataPos + chunkLen));
            }
            if (isChunkType(img, typePos, 'I', 'E', 'N', 'D')) {
                return Optional.empty();
            }
            pos = (int) (dataPos + chunkLen + 4); // skip data + CRC
        }
        return Optional.empty();
    }

    private static Optional<GpsCoordinates> extractFromWebP(byte[] img) {
        int pos = 12; // skip "RIFF" + size + "WEBP"
        int chunks = 0;
        while (pos + 8 <= img.length && chunks++ < MAX_SEGMENTS) {
            long chunkSize = u32(img, pos + 4, ByteOrder.LITTLE_ENDIAN);
            long dataPos = (long) pos + 8;
            if (chunkSize < 0 || dataPos + chunkSize > img.length) {
                return Optional.empty();
            }
            if (isChunkType(img, pos, 'E', 'X', 'I', 'F')) {
                int tiffStart = (int) dataPos;
                int tiffEnd = (int) (dataPos + chunkSize);
                if (tiffEnd - tiffStart >= 14 && isExifHeader(img, tiffStart)) {
                    tiffStart += 6;
                }
                return parseTiff(img, tiffStart, tiffEnd);
            }
            pos = (int) (dataPos + chunkSize + (chunkSize & 1)); // chunks are even-padded
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // TIFF / EXIF parsing (offsets relative to the TIFF header start)
    // ------------------------------------------------------------------

    private static Optional<GpsCoordinates> parseTiff(byte[] img, int start, int end) {
        if (end - start < 8 || start < 0 || end > img.length) {
            return Optional.empty();
        }
        ByteOrder order;
        if (img[start] == 'I' && img[start + 1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (img[start] == 'M' && img[start + 1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            return Optional.empty();
        }
        if (u16(img, start + 2, order) != 42) {
            return Optional.empty();
        }
        long ifd0Offset = u32(img, start + 4, order);
        Optional<Entry> gpsPointer = readEntry(img, start, end, order, ifd0Offset, TAG_GPS_IFD_POINTER);
        if (gpsPointer.isEmpty() || gpsPointer.get().type != TYPE_LONG || gpsPointer.get().count != 1) {
            return Optional.empty();
        }
        long gpsIfdOffset = gpsPointer.get().value;
        Optional<Entry> latRef = readEntry(img, start, end, order, gpsIfdOffset, TAG_LAT_REF);
        Optional<Entry> lat = readEntry(img, start, end, order, gpsIfdOffset, TAG_LATITUDE);
        Optional<Entry> lngRef = readEntry(img, start, end, order, gpsIfdOffset, TAG_LNG_REF);
        Optional<Entry> lng = readEntry(img, start, end, order, gpsIfdOffset, TAG_LONGITUDE);
        if (latRef.isEmpty() || lat.isEmpty() || lngRef.isEmpty() || lng.isEmpty()) {
            return Optional.empty();
        }
        Optional<double[]> latDms = readRationals(img, start, end, order, lat.get());
        Optional<double[]> lngDms = readRationals(img, start, end, order, lng.get());
        Optional<Character> latHemisphere = readAsciiChar(img, start, end, latRef.get());
        Optional<Character> lngHemisphere = readAsciiChar(img, start, end, lngRef.get());
        if (latDms.isEmpty() || lngDms.isEmpty() || latHemisphere.isEmpty() || lngHemisphere.isEmpty()) {
            return Optional.empty();
        }

        double latitude = toDecimal(latDms.get());
        double longitude = toDecimal(lngDms.get());
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return Optional.empty();
        }
        if (latHemisphere.get() == 'S' || latHemisphere.get() == 's') {
            latitude = -latitude;
        }
        if (lngHemisphere.get() == 'W' || lngHemisphere.get() == 'w') {
            longitude = -longitude;
        }
        if (Math.abs(latitude) > 90.0 || Math.abs(longitude) > 180.0) {
            return Optional.empty();
        }
        if (latitude == 0.0 && longitude == 0.0) {
            // Cameras write 0/0/0 when they have no fix; that is "no location",
            // not a pin in the Gulf of Guinea.
            return Optional.empty();
        }
        return Optional.of(new GpsCoordinates(latitude, longitude));
    }

    /**
     * One IFD entry: EXIF type, value count, the numeric value/offset, and the
     * absolute position of the 4-byte value field (inline bytes always occupy
     * the start of the field regardless of byte order).
     */
    private record Entry(int type, long count, long value, int fieldPos) {
    }

    private static Optional<Entry> readEntry(byte[] img, int start, int end, ByteOrder order,
                                             long ifdOffset, int wantedTag) {
        long ifdAbs = (long) start + ifdOffset;
        if (ifdOffset < 0 || ifdAbs + 2 > end) {
            return Optional.empty();
        }
        int count = u16(img, (int) ifdAbs, order);
        if (count < 0 || count > 512 || ifdAbs + 2 + (long) count * 12 + 4 > end) {
            return Optional.empty();
        }
        for (int i = 0; i < count; i++) {
            int entryPos = (int) (ifdAbs + 2 + (long) i * 12);
            if (u16(img, entryPos, order) == wantedTag) {
                int type = u16(img, entryPos + 2, order);
                long valueCount = u32(img, entryPos + 4, order);
                long value = u32(img, entryPos + 8, order);
                return Optional.of(new Entry(type, valueCount, value, entryPos + 8));
            }
        }
        return Optional.empty();
    }

    private static Optional<double[]> readRationals(byte[] img, int start, int end, ByteOrder order, Entry entry) {
        boolean signed = entry.type == TYPE_SRATIONAL;
        if ((!signed && entry.type != TYPE_RATIONAL) || entry.count < 3) {
            return Optional.empty();
        }
        // Three rationals never fit inline (24 bytes > 4-byte value field).
        long dataAbs = (long) start + entry.value;
        if (entry.value < 0 || dataAbs + 24 > end) {
            return Optional.empty();
        }
        double[] dms = new double[3];
        for (int i = 0; i < 3; i++) {
            int p = (int) (dataAbs + (long) i * 8);
            long num = signed ? i32(img, p, order) : u32(img, p, order);
            long den = signed ? i32(img, p + 4, order) : u32(img, p + 4, order);
            if (den == 0) {
                return Optional.empty();
            }
            dms[i] = (double) num / (double) den;
            if (!Double.isFinite(dms[i]) || dms[i] < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(dms);
    }

    private static Optional<Character> readAsciiChar(byte[] img, int start, int end, Entry entry) {
        if (entry.type != TYPE_ASCII || entry.count < 1) {
            return Optional.empty();
        }
        if (entry.count <= 4) {
            // Inline: the first byte of the value field, independent of byte order.
            return Optional.of((char) (img[entry.fieldPos()] & 0xFF));
        }
        long dataAbs = (long) start + entry.value;
        if (entry.value < 0 || entry.count > 64 || dataAbs + 1 > end) {
            return Optional.empty();
        }
        return Optional.of((char) (img[(int) dataAbs] & 0xFF));
    }

    private static double toDecimal(double[] dms) {
        return dms[0] + dms[1] / 60.0 + dms[2] / 3600.0;
    }

    // ------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------

    private static boolean isJpeg(byte[] img) {
        return (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8;
    }

    private static boolean isPng(byte[] img) {
        return img.length >= 8
                && (img[0] & 0xFF) == 137 && img[1] == 80 && img[2] == 78 && img[3] == 71
                && img[4] == 13 && img[5] == 10 && img[6] == 26 && img[7] == 10;
    }

    private static boolean isWebP(byte[] img) {
        return img.length >= 12
                && img[0] == 'R' && img[1] == 'I' && img[2] == 'F' && img[3] == 'F'
                && img[8] == 'W' && img[9] == 'E' && img[10] == 'B' && img[11] == 'P';
    }

    private static boolean isExifHeader(byte[] img, int pos) {
        return pos + 6 <= img.length
                && img[pos] == 'E' && img[pos + 1] == 'x' && img[pos + 2] == 'i' && img[pos + 3] == 'f'
                && img[pos + 4] == 0 && img[pos + 5] == 0;
    }

    private static boolean isChunkType(byte[] img, int pos, char a, char b, char c, char d) {
        return pos + 4 <= img.length
                && img[pos] == a && img[pos + 1] == b && img[pos + 2] == c && img[pos + 3] == d;
    }

    private static int u16(byte[] b, int p, ByteOrder order) {
        int a = b[p] & 0xFF;
        int c = b[p + 1] & 0xFF;
        return order == ByteOrder.LITTLE_ENDIAN ? (a | (c << 8)) : ((a << 8) | c);
    }

    private static long u32(byte[] b, int p, ByteOrder order) {
        long a = b[p] & 0xFF;
        long c = b[p + 1] & 0xFF;
        long d = b[p + 2] & 0xFF;
        long e = b[p + 3] & 0xFF;
        return order == ByteOrder.LITTLE_ENDIAN
                ? (a | (c << 8) | (d << 16) | (e << 24))
                : ((a << 24) | (c << 16) | (d << 8) | e);
    }

    private static long i32(byte[] b, int p, ByteOrder order) {
        int raw = (int) u32(b, p, order);
        return raw;
    }
}
