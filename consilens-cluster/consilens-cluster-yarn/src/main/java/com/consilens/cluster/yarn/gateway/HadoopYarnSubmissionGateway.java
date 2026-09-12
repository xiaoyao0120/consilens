package com.consilens.cluster.yarn.gateway;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Production YARN gateway backed by a real {@link YarnClient}. The client reads
 * {@code yarn-site.xml}/{@code core-site.xml} from the classpath like Spark/Flink
 * client utilities do; no shell invocation or hand-built REST submission is used.
 */
public class HadoopYarnSubmissionGateway implements YarnSubmissionGateway {

    private final Configuration configuration;
    private final YarnClient yarnClient;

    public HadoopYarnSubmissionGateway(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.yarnClient = YarnClient.createYarnClient();
        this.yarnClient.init(configuration);
        this.yarnClient.start();
    }

    @Override
    public ApplicationSubmissionContext createSubmissionContext() {
        try {
            return yarnClient.createApplication().getApplicationSubmissionContext();
        } catch (YarnException | IOException e) {
            throw new IllegalStateException("Unable to create YARN application", e);
        }
    }

    @Override
    public LocalResource createLocalResource(URI resourceUri, LocalResourceType type) {
        try {
            Path path = new Path(resourceUri);
            FileSystem fileSystem = path.getFileSystem(configuration);
            FileStatus status = fileSystem.getFileStatus(path);
            URL resourceUrl = URL.fromURI(resourceUri);
            return LocalResource.newInstance(resourceUrl, type, LocalResourceVisibility.APPLICATION,
                    status.getLen(), status.getModificationTime());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to resolve YARN local resource " + resourceUri, e);
        }
    }

    @Override
    public URI stageLocalFile(URI localUri, URI stagingBase) {
        try {
            Path source = new Path(localUri);
            FileSystem localFileSystem = FileSystem.getLocal(configuration);
            FileStatus status = localFileSystem.getFileStatus(source);
            if (status.isDir()) {
                throw new IllegalStateException("local artifact must be a file: " + localUri);
            }
            String fileName = source.getName();
            Path stagingDestination = new Path(directoryUri(stagingBase).resolve(UUID.randomUUID().toString() + "-" + fileName));
            FileSystem remoteFileSystem = stagingDestination.getFileSystem(configuration);
            FileUtil.copy(localFileSystem, source, remoteFileSystem, stagingDestination, false, configuration);
            return stagingDestination.toUri();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to stage local artifact " + localUri, e);
        }
    }

    private URI directoryUri(URI stagingBase) {
        String value = stagingBase.toString();
        return value.endsWith("/") ? stagingBase : URI.create(value + "/");
    }

    @Override
    public ApplicationId submit(ApplicationSubmissionContext context) {
        try {
            return yarnClient.submitApplication(context);
        } catch (YarnException | IOException e) {
            throw new IllegalStateException("YARN application submission failed", e);
        }
    }

    @Override
    public String resourceManagerHostname() {
        String hostname = configuration.get(YarnConfiguration.RM_HOSTNAME);
        if (hostname != null && !hostname.trim().isEmpty()) {
            return hostname.trim();
        }
        String address = configuration.get(YarnConfiguration.RM_ADDRESS);
        if (address == null) {
            return null;
        }
        int portSeparator = address.lastIndexOf(':');
        return portSeparator > 0 ? address.substring(0, portSeparator) : address;
    }

    @Override
    public void close() {
        try {
            yarnClient.close();
        } catch (IOException e) {
            // The application was already submitted to the ResourceManager; a failed
            // client shutdown must not turn a successful submission into an error.
        }
    }
}
