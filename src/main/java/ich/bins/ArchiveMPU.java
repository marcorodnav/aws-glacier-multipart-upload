package ich.bins;

import com.amazonaws.AmazonClientException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.glacier.AmazonGlacier;
import com.amazonaws.services.glacier.AmazonGlacierClientBuilder;
import com.amazonaws.services.glacier.TreeHashGenerator;
import com.amazonaws.services.glacier.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.glacier.model.CompleteMultipartUploadResult;
import com.amazonaws.services.glacier.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.glacier.model.InitiateMultipartUploadResult;
import com.amazonaws.services.glacier.model.UploadMultipartPartResult;
import com.amazonaws.util.BinaryUtils;
import ich.bins.ArchiveMPUParser.Option;
import net.jbock.CommandLineArguments;
import net.jbock.Description;
import net.jbock.LongName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class ArchiveMPU {

  private static final int partSize = 1048576; // 1 MB.
  private static final int clientLife = 60;
  private static final int threads = 4;

  private static final Logger log = LoggerFactory.getLogger(ArchiveMPU.class);

  private final String fileToUpload;
  private final String description;
  final String vaultName;
  private final String serviceEndpoint;
  private final String signingRegion;

  @CommandLineArguments
  ArchiveMPU(@LongName("file")
             @Description("file to upload")
                 String fileToUpload,
             @LongName("description")
             @Description("intended archive name in vault")
                 String description,
             @LongName("vault-name")
             @Description("aws glacier vault name; this vault must already exist")
                 String vaultName,
             @LongName("service-endpoint")
             @Description("aws service endpoint, e.g. 'glacier.eu-central-1.amazonaws.com'")
                 String serviceEndpoint,
             @LongName("signing-region")
             @Description("aws signing region, e.g. 'eu-central-1'")
                 String signingRegion) {
    this.fileToUpload = fileToUpload;
    this.description = description;
    this.vaultName = vaultName;
    this.serviceEndpoint = serviceEndpoint;
    this.signingRegion = signingRegion;
  }

  public static void main(String[] args) throws IOException {
    ArchiveMPUParser parser = ArchiveMPUParser.init(args);
    List<ArchiveMPUParser.Argument> missing = parser.arguments()
        .stream()
        .filter(p -> p.value == null)
        .collect(Collectors.toList());
    if (!missing.isEmpty()) {
      System.out.println("Required options:");
      ArchiveMPUParser.options().stream()
          .map(Option::describe)
          .forEach(System.out::println);
      System.out.println("Missing required options:");
      missing.stream().map(argument -> argument.option)
          .map(Option::describe)
          .forEach(System.out::println);
      System.exit(1);
    }
    ArchiveMPU archiveMPU = parser.parse();
    try {
      String fileToUpload = args[0];
      String description = args[1];
      String vaultName = args[2];
      String serviceEndpoint = args[3];
      String signingRegion = args[4];

      log.info("------------");
      log.info("fileToUpload: " + fileToUpload);
      log.info("description: " + description);
      log.info("vaultName: " + vaultName);
      log.info("serviceEndpoint: " + serviceEndpoint);
      log.info("signingRegion: " + signingRegion);
      log.info("------------");

      log.info("File size: " + new File(fileToUpload).length());

      archiveMPU = new ArchiveMPU(fileToUpload, description, vaultName, serviceEndpoint, signingRegion);

      InitiateMultipartUploadResult initiateUploadResult = archiveMPU.initiateMultipartUpload();
      log.info(initiateUploadResult.toString());
      String uploadId = initiateUploadResult.getUploadId();
      String checksum = archiveMPU.uploadParts(uploadId);
      CompleteMultipartUploadResult result = archiveMPU.completeMultiPartUpload(
          uploadId,
          checksum);
      log.info("Upload finished\n:" + result);
    } catch (Exception e) {
      log.error("Error", e);
    } finally {
      archiveMPU.client().shutdown();
    }
  }

  private AmazonGlacier client = null;
  private final AtomicLong connectionCount = new AtomicLong();

  synchronized AmazonGlacier client() {
    if (client == null) {
      client = _client();
    }
    if (connectionCount.incrementAndGet() % clientLife == 0) {
      client.shutdown();
      client = _client();
    }
    return client;
  }

  private AmazonGlacier _client() {
    return AmazonGlacierClientBuilder.standard()
        .withCredentials(new ProfileCredentialsProvider())
        .withEndpointConfiguration(
            new AwsClientBuilder.EndpointConfiguration(
                serviceEndpoint, signingRegion))
        .withClientConfiguration(new ClientConfiguration())
        .build();
  }

  private InitiateMultipartUploadResult initiateMultipartUpload() {
    // Initiate
    InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest()
        .withVaultName(vaultName)
        .withArchiveDescription(description)
        .withPartSize(Integer.toString(partSize));

    return client().initiateMultipartUpload(request);
  }

  private String uploadParts(
      String uploadId) throws
      NoSuchAlgorithmException,
      AmazonClientException,
      IOException, InterruptedException {
    long currentPosition = 0;
    List<UploadPartCommand> commands = new LinkedList<>();

    File file = new File(fileToUpload);

    AtomicInteger numParts = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();

    try (final FileInputStream fileToUpload = new FileInputStream(file)) {
      while (currentPosition < file.length()) {
        UploadPartCommand command = uploadPart(numParts,
            completed,
            uploadId,
            currentPosition,
            fileToUpload);
        if (command.length() == 0) {
          break;
        }
        commands.add(command);
        currentPosition += command.length();
      }
    }

    long totalLength =
        commands.stream().mapToInt(UploadPartCommand::length).sum();

    if (totalLength != file.length()) {
      throw new IllegalStateException("File size is " + file.length() +
          " but sum of parts is " + totalLength);
    }

    List<byte[]> binaryChecksums = commands.stream()
        .map(command -> BinaryUtils.fromHex(command.checksum))
        .collect(Collectors.toList());
    numParts.set(binaryChecksums.size());
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<UploadMultipartPartResult>> futures = pool.invokeAll(commands);
    boolean success = futures.stream().allMatch(f -> {
      try {
        f.get();
        return true;
      } catch (Exception e) {
        log.error("Error", e);
        return false;
      }
    });
    pool.shutdown();
    if (success) {
      return TreeHashGenerator.calculateTreeHash(binaryChecksums);
    } else {
      throw new IllegalStateException("Some uploads have failed");
    }
  }

  private UploadPartCommand uploadPart(
      AtomicInteger numParts,
      AtomicInteger completed,
      final String uploadId,
      final long currentPosition,
      final FileInputStream fileToUpload) throws IOException {
    byte[] buffer = new byte[partSize];
    int read = fileToUpload.read(buffer, 0, buffer.length);
    if (read < 0) {
      return new UploadPartCommand(this, numParts, completed, currentPosition, null, uploadId);
    }
    byte[] bytesRead = Arrays.copyOf(buffer, read);
    return new UploadPartCommand(this, numParts, completed, currentPosition, bytesRead, uploadId);
  }

  private CompleteMultipartUploadResult completeMultiPartUpload(
      String uploadId,
      String checksum) throws IOException {

    File file = new File(fileToUpload);

    CompleteMultipartUploadRequest compRequest = new CompleteMultipartUploadRequest()
        .withVaultName(vaultName)
        .withUploadId(uploadId)
        .withChecksum(checksum)
        .withArchiveSize(String.valueOf(file.length()));

    return client().completeMultipartUpload(compRequest);
  }
}