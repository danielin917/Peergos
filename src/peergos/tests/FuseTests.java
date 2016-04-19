package peergos.tests;

import org.junit.*;
import peergos.corenode.*;
import peergos.crypto.*;
import peergos.fuse.*;
import peergos.server.Start;
import peergos.user.*;
import peergos.util.*;

import java.io.*;
import java.lang.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.*;

public class FuseTests {
    public static int WEB_PORT = 8888;
    public static int CORE_PORT = 7777;
    public static String username = "test02";
    public static String password = username;
    public static Path mountPoint, home;
    public static FuseProcess fuseProcess;

    public static void setWebPort(int webPort) {
        WEB_PORT = webPort;
    }

    public static void setCorePort(int corePort) {
        CORE_PORT = corePort;
    }

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    public static UserContext ensureSignedUp(String username, String password) throws IOException {
        DHTClient.HTTP dht = new DHTClient.HTTP(new URL("http://localhost:"+ WEB_PORT +"/"));
        Btree.HTTP btree = new Btree.HTTP(new URL("http://localhost:"+ WEB_PORT +"/"));
        HTTPCoreNode coreNode = new HTTPCoreNode(new URL("http://localhost:"+ WEB_PORT +"/"));
        UserContext userContext = UserContext.ensureSignedUp(username, password, dht, btree, coreNode);
        return userContext;
    }

    @BeforeClass
    public static void init() throws Exception {
        Random  random  = new Random();
        int offset = random.nextInt(100);
        setWebPort(8888 + offset);
        setCorePort(7777 + offset);

        System.out.println("Using web-port "+ WEB_PORT);
        System.out.flush();
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));

        Args.parse(new String[]{"useIPFS", "false",
                "-port", Integer.toString(WEB_PORT),
                "-corenodePort", Integer.toString(CORE_PORT)});

        Start.local();
        UserContext userContext = ensureSignedUp(username, password);

        String mountPath = Args.getArg("mountPoint", "/tmp/peergos/tmp");

        mountPoint = Paths.get(mountPath);
        mountPoint = mountPoint.resolve(UUID.randomUUID().toString());
        mountPoint.toFile().mkdirs();
        home = mountPoint.resolve(username);

        System.out.println("\n\nMountpoint "+ mountPoint +"\n\n");
        PeergosFS peergosFS = new PeergosFS(userContext);
        fuseProcess = new FuseProcess(peergosFS, mountPoint);

        Runtime.getRuntime().addShutdownHook(new Thread(()  -> fuseProcess.close()));

        fuseProcess.start();
    }

    public static String readStdout(Process p) throws IOException {
        return new String(Serialize.readFully(p.getInputStream())).trim();
    }

    @Test
    public void variousTests() throws IOException {
        boolean homeExists = Stream.of(mountPoint.toFile().listFiles())
                .map(f -> f.getName())
                .filter(n -> n.equals(username))
                .findAny()
                .isPresent();
        Assert.assertTrue("Correct home directory: " + homeExists, homeExists);

        Path home = mountPoint.resolve(username);

        // write a small file
        Path filename1 = home.resolve("data.txt");
        String msg = "Hello Peergos!";

        Files.write(filename1, msg.getBytes());

        byte[] smallFileContents = Files.readAllBytes(filename1);
        Assert.assertTrue("Correct file contents: " + msg, Arrays.equals(smallFileContents, msg.getBytes()));


        // rename a file
        Path newFileName = home.resolve("moredata.txt");
        boolean updatedFileAlreadyExists = newFileName.toFile().exists();
        Assert.assertFalse("updated file "+  newFileName+" doesn't already exist", updatedFileAlreadyExists);

        Files.move(filename1, newFileName);
        byte[] movedContents  = Files.readAllBytes(newFileName);

        Assert.assertTrue("Correct moved file contents", Arrays.equals(movedContents, msg.getBytes()));

        // mkdir
        Path directory = home.resolve("adirectory");

        Supplier<Boolean> directoryExists = () -> directory.toFile().exists();
        Assert.assertFalse("directory "+ directory +" doesn't already exist", directoryExists.get());

        directory.toFile().mkdir();

        Assert.assertTrue("Mkdir exists", directoryExists.get());

        //move a file to a different directory (calls rename)
        Path inDir = directory.resolve(newFileName.getFileName());
        Supplier<Boolean> inDirExists = () -> inDir.toFile().exists();
        Assert.assertFalse("new file in directory "+ inDir +" doesn't already exist", inDirExists.get());

        Files.move(newFileName, inDir);

        Assert.assertTrue("new file in directory "+ inDir +" exist", inDirExists.get());

        byte[] inDirContents =  Files.readAllBytes(inDir);

        Assert.assertTrue("Correct file contents after move to another directory", Arrays.equals(inDirContents, msg.getBytes()));
    }

    private void fileTest(int length, Random random)  throws IOException {
        byte[] data = new byte[length];
        random.nextBytes(data);

        String filename = UserTests.randomString();
        Path path = home.resolve(filename);

        Files.write(path, data);

        byte[] contents = Files.readAllBytes(path);

        Assert.assertTrue("Correct file contents for length "+ length, Arrays.equals(data, contents));
    }

    @Test
    public void readWriteTest() throws IOException {
        Random  random =  new Random(666); // repeatable with same seed
        for (int power = 5; power < 20; power++) {
            int length =  (int) Math.pow(2, power);
            length +=  random.nextInt(length);
            fileTest(length, random);
        }
    }


    private static void runForAWhile() {
        for (int i=0; i < 600; i++)
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
    }

    @AfterClass
    public static void shutdown() {
        fuseProcess.close();
    }
}
