import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/** Measures decompress wall time + throughput for bzip2 (pure Java) vs gzip/zip (native zlib). */
public class DecompressBench {
    public static void main(String[] args) throws Exception {
        // arg: <file>  where extension decides codec: .bz2 -> commons-compress, .gz -> gzip, .zip -> zip
        String path = args[0];
        long start = System.nanoTime();
        long bytes = 0;
        if (path.endsWith(".bz2")) {
            try (InputStream in = new BZip2CompressorInputStream(
                    new BufferedInputStream(new FileInputStream(path), 1 << 20))) {
                bytes = drain(in);
            }
        } else if (path.endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(new FileInputStream(path), 1 << 16)) {
                bytes = drain(in);
            }
        } else if (path.endsWith(".zip")) {
            try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(path), 1 << 20))) {
                in.getNextEntry();
                bytes = drain(in);
            }
        } else {
            throw new IllegalArgumentException("unknown codec for " + path);
        }
        double secs = (System.nanoTime() - start) / 1e9;
        System.out.printf("%s: %.1f MB in %.2fs -> %.1f MB/s%n",
                new File(path).getName(), bytes / 1e6, secs, bytes / 1e6 / secs);
    }

    private static long drain(InputStream in) throws Exception {
        byte[] buf = new byte[1 << 20];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) total += n;
        return total;
    }
}
