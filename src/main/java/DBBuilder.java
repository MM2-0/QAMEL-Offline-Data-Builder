import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;

public class DBBuilder {
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        if (args.length < 2) {
            printHelp();
            return;
        }

        try {
            File outputDir = new File("out");
            File outputFile = new File(outputDir, "out.ttl");
            if (outputDir.exists() && System.console().readLine("W: Output directory already exists and will be " +
                    "overwritten. Continue? [Y/n] ").toLowerCase().startsWith("n")) {
                return;
            } else {
                if(outputDir.exists()) {
                    System.out.println("I: Deleting existing out directory...");
                    FileUtils.deleteDirectory(outputDir);
                    System.out.println("I:  --> Done.");
                }
            }
            outputDir.mkdirs();
            File inputFile = new File(args[0]);
            System.out.println("I: Reading entities from input file " + inputFile.getPath() + "...");
            int threshold = Integer.parseInt(args[1]);
            HashMap<String, Integer> index = new HashMap<>();
            BufferedReader reader = new BufferedReader(new FileReader(inputFile));
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith("#")) {
                    line = reader.readLine();
                    continue;
                }
                String subj = line.substring(0, line.indexOf(' '));
                line = line.substring(line.indexOf(' ') + 1);
                String pred = line.substring(0, line.indexOf(' '));
                line = line.substring(line.indexOf(' ') + 1);
                String obj;
                if (!line.startsWith("<")) {
                    obj = null;
                } else {
                    obj = line.substring(0, line.lastIndexOf('>') + 1);
                }

                if (index.containsKey(subj)) {
                    index.put(subj, index.get(subj) + 1);
                } else {
                    index.put(subj, 1);
                }
                if (index.containsKey(pred)) {
                    index.put(pred, index.get(pred) + 1);
                } else {
                    index.put(pred, 1);
                }
                if (obj != null) {
                    if (index.containsKey(obj)) {
                        index.put(obj, index.get(obj) + 1);
                    } else {
                        index.put(obj, 1);
                    }
                }
                line = reader.readLine();
            }
            reader.close();
            System.out.println("I:  --> " + index.entrySet().size() + " entities found.");
            System.out.println("I: Writing relevant triples...");
            reader = new BufferedReader(new FileReader(inputFile));
            PrintWriter writer = new PrintWriter(new FileWriter(outputFile));
            line = reader.readLine();
            int triples = 0;
            while (line != null) {
                if (line.startsWith("#")) {
                    line = reader.readLine();
                    continue;
                }
                String triple = line;
                String subj = line.substring(0, line.indexOf(' '));
                line = line.substring(line.indexOf(' ') + 1);
                String pred = line.substring(0, line.indexOf(' '));
                line = line.substring(line.indexOf(' ') + 1);
                String obj;
                if (!line.startsWith("<")) {
                    obj = "literal";
                } else {
                    obj = line.substring(0, line.lastIndexOf('>') + 1);
                }
                int matches = 0;
                if (index.get(subj) >= threshold) matches++;
                if (index.get(pred) >= threshold) matches++;
                if (!obj.startsWith("<") || index.get(obj) >= threshold) matches++;
                if (matches == 3) {
                    writer.println(triple);
                    triples++;
                }
                line = reader.readLine();
            }
            writer.close();
            index = null;
            String revision = getSHA256(outputFile);
            System.out.println("I:  --> " + triples + " triples have been written.");
            System.out.println("I: " + "Writing database...");
            File databaseDir = new File(outputDir, "offline_data");
            Repository db = new SailRepository(new NativeStore(databaseDir));
            db.initialize();
            RepositoryConnection connection = db.getConnection();
            InputStream inputStream = new FileInputStream(outputFile);
            connection.add(inputStream, "", RDFFormat.NTRIPLES);
            db.shutDown();
            writer = new PrintWriter(new FileWriter(new File(databaseDir, "revision")));
            writer.print(revision);
            writer.close();
            System.out.println("I:  --> Database successfully created.");
            System.out.println("I: Compressing database...");
            File tarGz = new File(databaseDir.getPath() + ".tar.gz");
            createTarGZ(databaseDir, tarGz);
            System.out.println("I:  --> Database has been compressed into " + tarGz.getPath());
            Date timeDiff = new Date(System.currentTimeMillis() - startTime);
            DateFormat df = new SimpleDateFormat("HH:mm:ss");
            df.setTimeZone(TimeZone.getTimeZone("GMT"));
            System.out.println("I:  FINISHED. (" + df.format(timeDiff) +")");
            System.out.println("I:   --> Offline data has been created successfully.");
            System.out.println("I:   --> Output file: " + tarGz.getPath());
            System.out.println("I:   --> Revision: " + revision);

        } catch (FileNotFoundException e) {
            System.err.println("E: input file not found!");
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java -Xmx4g -jar dbbuilder.jar <input file> <threshold>");
    }

    private static void createTarGZ(File dir, File tarGz)
            throws FileNotFoundException, IOException {
        FileOutputStream fOut = new FileOutputStream(tarGz);
        BufferedOutputStream bOut = new BufferedOutputStream(fOut);
        GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(bOut);
        TarArchiveOutputStream tOut = new TarArchiveOutputStream(gzOut);
        addFileToTarGz(tOut, dir, "");
        tOut.finish();
        tOut.close();
        gzOut.close();
        bOut.close();
        fOut.close();
    }

    private static void addFileToTarGz(TarArchiveOutputStream tOut, File f, String base)
            throws IOException {
        String entryName = base + f.getName();
        TarArchiveEntry tarEntry = new TarArchiveEntry(f, entryName);
        tOut.putArchiveEntry(tarEntry);

        if (f.isFile()) {
            IOUtils.copy(new FileInputStream(f), tOut);
            tOut.closeArchiveEntry();
        } else {
            tOut.closeArchiveEntry();
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    addFileToTarGz(tOut, child, entryName + "/");
                }
            }
        }
    }

    private static String getSHA256(File file) throws NoSuchAlgorithmException, IOException {

        FileInputStream inputStream = new FileInputStream(file);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        byte[] bytesBuffer = new byte[1024];
        int bytesRead = -1;

        while ((bytesRead = inputStream.read(bytesBuffer)) != -1) {
            digest.update(bytesBuffer, 0, bytesRead);
        }

        byte[] hashedBytes = digest.digest();

        return convertByteArrayToHexString(hashedBytes);
    }

    private static String convertByteArrayToHexString(byte[] arrayBytes) {
        StringBuilder stringBuffer = new StringBuilder();
        for (byte arrayByte : arrayBytes) {
            stringBuffer.append(Integer.toString((arrayByte & 0xff) + 0x100, 16)
                    .substring(1));
        }
        return stringBuffer.toString();
    }
}
