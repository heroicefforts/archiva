package org.apache.archiva.proxy.maven;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.archiva.configuration.NetworkProxyConfiguration;
import org.apache.archiva.model.RepositoryURL;
import org.apache.archiva.proxy.DefaultRepositoryProxyHandler;
import org.apache.archiva.proxy.NotFoundException;
import org.apache.archiva.proxy.NotModifiedException;
import org.apache.archiva.proxy.ProxyException;
import org.apache.archiva.proxy.model.NetworkProxy;
import org.apache.archiva.proxy.model.ProxyConnector;
import org.apache.archiva.repository.*;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.WagonException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.repository.Repository;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * DefaultRepositoryProxyHandler
 * TODO exception handling needs work - "not modified" is not really an exceptional case, and it has more layers than
 * your average brown onion
 */
@Service("repositoryProxyConnectors#maven")
public class MavenRepositoryProxyHandler extends DefaultRepositoryProxyHandler {

    private static final List<RepositoryType> REPOSITORY_TYPES = new ArrayList<>();

    static {
        REPOSITORY_TYPES.add(RepositoryType.MAVEN);
    }

    @Inject
    private WagonFactory wagonFactory;

    private ConcurrentMap<String, ProxyInfo> networkProxyMap = new ConcurrentHashMap<>();

    @Override
    public void initialize() {
        super.initialize();
    }

    private void updateWagonProxyInfo(Map<String, NetworkProxy> proxyList) {
        this.networkProxyMap.clear();
        List<NetworkProxyConfiguration> networkProxies = getArchivaConfiguration().getConfiguration().getNetworkProxies();
        for (Map.Entry<String, NetworkProxy> proxyEntry : proxyList.entrySet()) {
            String key = proxyEntry.getKey();
            NetworkProxy networkProxyDef = proxyEntry.getValue();

            ProxyInfo proxy = new ProxyInfo();

            proxy.setType(networkProxyDef.getProtocol());
            proxy.setHost(networkProxyDef.getHost());
            proxy.setPort(networkProxyDef.getPort());
            proxy.setUserName(networkProxyDef.getUsername());
            proxy.setPassword(networkProxyDef.getPassword());

            this.networkProxyMap.put(key, proxy);
        }
    }

    @Override
    public void setNetworkProxies(Map<String, NetworkProxy> proxies) {
        super.setNetworkProxies(proxies);
        updateWagonProxyInfo(proxies);
    }

    /**
     * @param connector
     * @param remoteRepository
     * @param tmpMd5
     * @param tmpSha1
     * @param tmpResource
     * @param url
     * @param remotePath
     * @param resource
     * @param workingDirectory
     * @param repository
     * @throws ProxyException
     * @throws NotModifiedException
     */
    protected void transferResources(ProxyConnector connector, RemoteRepositoryContent remoteRepository, Path tmpMd5,
                                     Path tmpSha1, Path tmpResource, String url, String remotePath, Path resource,
                                     Path workingDirectory, ManagedRepositoryContent repository)
            throws ProxyException, NotModifiedException {
        Wagon wagon = null;
        try {
            RepositoryURL repoUrl = remoteRepository.getURL();
            String protocol = repoUrl.getProtocol();
            NetworkProxy networkProxy = null;
            String proxyId = connector.getProxyId();
            if (StringUtils.isNotBlank(proxyId)) {

                networkProxy = getNetworkProxy(proxyId);
            }
            WagonFactoryRequest wagonFactoryRequest = new WagonFactoryRequest("wagon#" + protocol,
                    remoteRepository.getRepository().getExtraHeaders());
            if (networkProxy == null) {

                log.warn("No network proxy with id {} found for connector {}->{}", proxyId,
                        connector.getSourceRepository().getId(), connector.getTargetRepository().getId());
            } else {
                wagonFactoryRequest = wagonFactoryRequest.networkProxy(networkProxy);
            }
            wagon = wagonFactory.getWagon(wagonFactoryRequest);
            if (wagon == null) {
                throw new ProxyException("Unsupported target repository protocol: " + protocol);
            }

            if (wagon == null) {
                throw new ProxyException("Unsupported target repository protocol: " + protocol);
            }

            boolean connected = connectToRepository(connector, wagon, remoteRepository);
            if (connected) {
                transferArtifact(wagon, remoteRepository, remotePath, repository, resource, workingDirectory,
                        tmpResource);

                // TODO: these should be used to validate the download based on the policies, not always downloaded
                // to
                // save on connections since md5 is rarely used
                transferChecksum(wagon, remoteRepository, remotePath, repository, resource, workingDirectory, ".sha1",
                        tmpSha1);
                transferChecksum(wagon, remoteRepository, remotePath, repository, resource, workingDirectory, ".md5",
                        tmpMd5);
            }
        } catch (NotFoundException e) {
            urlFailureCache.cacheFailure(url);
            throw e;
        } catch (NotModifiedException e) {
            // Do not cache url here.
            throw e;
        } catch (ProxyException e) {
            urlFailureCache.cacheFailure(url);
            throw e;
        } catch (WagonFactoryException e) {
            throw new ProxyException(e.getMessage(), e);
        } finally {
            if (wagon != null) {
                try {
                    wagon.disconnect();
                } catch (ConnectionException e) {
                    log.warn("Unable to disconnect wagon.", e);
                }
            }
        }
    }

    protected void transferArtifact(Wagon wagon, RemoteRepositoryContent remoteRepository, String remotePath,
                                    ManagedRepositoryContent repository, Path resource, Path tmpDirectory,
                                    Path destFile)
            throws ProxyException {
        transferSimpleFile(wagon, remoteRepository, remotePath, repository, resource, destFile);
    }

    /**
     * <p>
     * Quietly transfer the checksum file from the remote repository to the local file.
     * </p>
     *
     * @param wagon            the wagon instance (should already be connected) to use.
     * @param remoteRepository the remote repository to transfer from.
     * @param remotePath       the remote path to the resource to get.
     * @param repository       the managed repository that will hold the file
     * @param resource         the local file that should contain the downloaded contents
     * @param tmpDirectory     the temporary directory to download to
     * @param ext              the type of checksum to transfer (example: ".md5" or ".sha1")
     * @throws ProxyException if copying the downloaded file into place did not succeed.
     */
    protected void transferChecksum(Wagon wagon, RemoteRepositoryContent remoteRepository, String remotePath,
                                    ManagedRepositoryContent repository, Path resource, Path tmpDirectory, String ext,
                                    Path destFile)
            throws ProxyException {
        String url = remoteRepository.getURL().getUrl() + remotePath + ext;

        // Transfer checksum does not use the policy.
        if (urlFailureCache.hasFailedBefore(url)) {
            return;
        }

        try {
            transferSimpleFile(wagon, remoteRepository, remotePath + ext, repository, resource, destFile);
            log.debug("Checksum {} Downloaded: {} to move to {}", url, destFile, resource);
        } catch (NotFoundException e) {
            urlFailureCache.cacheFailure(url);
            log.debug("Transfer failed, checksum not found: {}", url);
            // Consume it, do not pass this on.
        } catch (NotModifiedException e) {
            log.debug("Transfer skipped, checksum not modified: {}", url);
            // Consume it, do not pass this on.
        } catch (ProxyException e) {
            urlFailureCache.cacheFailure(url);
            log.warn("Transfer failed on checksum: {} : {}", url, e.getMessage(), e);
            // Critical issue, pass it on.
            throw e;
        }
    }

    /**
     * Perform the transfer of the remote file to the local file specified.
     *
     * @param wagon            the wagon instance to use.
     * @param remoteRepository the remote repository to use
     * @param remotePath       the remote path to attempt to get
     * @param repository       the managed repository that will hold the file
     * @param origFile         the local file to save to
     * @throws ProxyException if there was a problem moving the downloaded file into place.
     */
    protected void transferSimpleFile(Wagon wagon, RemoteRepositoryContent remoteRepository, String remotePath,
                                      ManagedRepositoryContent repository, Path origFile, Path destFile)
            throws ProxyException {
        assert (remotePath != null);

        // Transfer the file.
        try {
            boolean success = false;

            if (!Files.exists(origFile)) {
                log.debug("Retrieving {} from {}", remotePath, remoteRepository.getRepository().getName());
                wagon.get(addParameters(remotePath, remoteRepository.getRepository()), destFile.toFile());
                success = true;

                // You wouldn't get here on failure, a WagonException would have been thrown.
                log.debug("Downloaded successfully.");
            } else {
                log.debug("Retrieving {} from {} if updated", remotePath, remoteRepository.getRepository().getName());
                try {
                    success = wagon.getIfNewer(addParameters(remotePath, remoteRepository.getRepository()), destFile.toFile(),
                            Files.getLastModifiedTime(origFile).toMillis());
                } catch (IOException e) {
                    throw new ProxyException("Failed to the modification time of " + origFile.toAbsolutePath());
                }
                if (!success) {
                    throw new NotModifiedException(
                            "Not downloaded, as local file is newer than remote side: " + origFile.toAbsolutePath());
                }

                if (Files.exists(destFile)) {
                    log.debug("Downloaded successfully.");
                }
            }
        } catch (ResourceDoesNotExistException e) {
            throw new NotFoundException(
                    "Resource [" + remoteRepository.getURL() + "/" + remotePath + "] does not exist: " + e.getMessage(),
                    e);
        } catch (WagonException e) {
            // TODO: shouldn't have to drill into the cause, but TransferFailedException is often not descriptive enough

            String msg =
                    "Download failure on resource [" + remoteRepository.getURL() + "/" + remotePath + "]:" + e.getMessage();
            if (e.getCause() != null) {
                msg += " (cause: " + e.getCause() + ")";
            }
            throw new ProxyException(msg, e);
        }
    }

    /**
     * Using wagon, connect to the remote repository.
     *
     * @param connector        the connector configuration to utilize (for obtaining network proxy configuration from)
     * @param wagon            the wagon instance to establish the connection on.
     * @param remoteRepository the remote repository to connect to.
     * @return true if the connection was successful. false if not connected.
     */
    protected boolean connectToRepository(ProxyConnector connector, Wagon wagon,
                                          RemoteRepositoryContent remoteRepository) {
        boolean connected = false;

        final ProxyInfo networkProxy =
                connector.getProxyId() == null ? null : this.networkProxyMap.get(connector.getProxyId());

        if (log.isDebugEnabled()) {
            if (networkProxy != null) {
                // TODO: move to proxyInfo.toString()
                String msg = "Using network proxy " + networkProxy.getHost() + ":" + networkProxy.getPort()
                        + " to connect to remote repository " + remoteRepository.getURL();
                if (networkProxy.getNonProxyHosts() != null) {
                    msg += "; excluding hosts: " + networkProxy.getNonProxyHosts();
                }
                if (StringUtils.isNotBlank(networkProxy.getUserName())) {
                    msg += "; as user: " + networkProxy.getUserName();
                }
                log.debug(msg);
            }
        }

        AuthenticationInfo authInfo = null;
        String username = "";
        String password = "";
        RepositoryCredentials repCred = remoteRepository.getRepository().getLoginCredentials();
        if (repCred != null && repCred instanceof PasswordCredentials) {
            PasswordCredentials pwdCred = (PasswordCredentials) repCred;
            username = pwdCred.getUsername();
            password = pwdCred.getPassword() == null ? "" : new String(pwdCred.getPassword());
        }

        if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            log.debug("Using username {} to connect to remote repository {}", username, remoteRepository.getURL());
            authInfo = new AuthenticationInfo();
            authInfo.setUserName(username);
            authInfo.setPassword(password);
        }

        // Convert seconds to milliseconds

        long timeoutInMilliseconds = remoteRepository.getRepository().getTimeout().toMillis();

        // Set timeout  read and connect
        // FIXME olamy having 2 config values
        wagon.setReadTimeout((int) timeoutInMilliseconds);
        wagon.setTimeout((int) timeoutInMilliseconds);

        try {
            Repository wagonRepository =
                    new Repository(remoteRepository.getId(), remoteRepository.getURL().toString());
            wagon.connect(wagonRepository, authInfo, networkProxy);
            connected = true;
        } catch (ConnectionException | AuthenticationException e) {
            log.warn("Could not connect to {}: {}", remoteRepository.getRepository().getName(), e.getMessage());
            connected = false;
        }

        return connected;
    }


    public WagonFactory getWagonFactory() {
        return wagonFactory;
    }

    public void setWagonFactory(WagonFactory wagonFactory) {
        this.wagonFactory = wagonFactory;
    }

    @Override
    public List<RepositoryType> supports() {
        return REPOSITORY_TYPES;
    }
}
