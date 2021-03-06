package com.webank.wecube.platform.core.service.plugin;

import static com.google.common.collect.Lists.newArrayList;
import static org.apache.commons.lang3.StringUtils.trim;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;
import com.webank.wecube.platform.core.commons.ApplicationProperties.PluginProperties;
import com.webank.wecube.platform.core.commons.ApplicationProperties.ResourceProperties;
import com.webank.wecube.platform.core.commons.WecubeCoreException;
import com.webank.wecube.platform.core.domain.ResourceItem;
import com.webank.wecube.platform.core.domain.ResourceServer;
import com.webank.wecube.platform.core.domain.plugin.PluginInstance;
import com.webank.wecube.platform.core.domain.plugin.PluginMysqlInstance;
import com.webank.wecube.platform.core.domain.plugin.PluginPackage;
import com.webank.wecube.platform.core.domain.plugin.PluginPackageRuntimeResourcesDocker;
import com.webank.wecube.platform.core.domain.plugin.PluginPackageRuntimeResourcesMysql;
import com.webank.wecube.platform.core.domain.plugin.PluginPackageRuntimeResourcesS3;
import com.webank.wecube.platform.core.dto.CreateInstanceDto;
import com.webank.wecube.platform.core.dto.QueryRequest;
import com.webank.wecube.platform.core.dto.ResourceItemDto;
import com.webank.wecube.platform.core.dto.ResourceServerDto;
import com.webank.wecube.platform.core.jpa.PluginConfigRepository;
import com.webank.wecube.platform.core.jpa.PluginInstanceRepository;
import com.webank.wecube.platform.core.jpa.PluginMysqlInstanceRepository;
import com.webank.wecube.platform.core.jpa.PluginPackageRepository;
import com.webank.wecube.platform.core.jpa.ResourceItemRepository;
import com.webank.wecube.platform.core.jpa.ResourceServerRepository;
import com.webank.wecube.platform.core.service.CommandService;
import com.webank.wecube.platform.core.service.ScpService;
import com.webank.wecube.platform.core.service.SystemVariableService;
import com.webank.wecube.platform.core.service.resource.ResourceItemType;
import com.webank.wecube.platform.core.service.resource.ResourceManagementService;
import com.webank.wecube.platform.core.service.resource.ResourceServerType;
import com.webank.wecube.platform.core.support.S3Client;
import com.webank.wecube.platform.core.support.gateway.GatewayResponse;
import com.webank.wecube.platform.core.support.gateway.GatewayServiceStub;
import com.webank.wecube.platform.core.support.gateway.RegisterRouteItemsDto;
import com.webank.wecube.platform.core.support.gateway.RouteItem;
import com.webank.wecube.platform.core.utils.EncryptionUtils;
import com.webank.wecube.platform.core.utils.JsonUtils;
import com.webank.wecube.platform.core.utils.StringUtilsEx;
import com.webank.wecube.platform.core.utils.SystemUtils;

@Service
public class PluginInstanceService {
    private static final Logger logger = LoggerFactory.getLogger(PluginInstanceService.class);

    @Autowired
    private PluginProperties pluginProperties;

    @Autowired
    PluginInstanceRepository pluginInstanceRepository;
    @Autowired
    PluginPackageRepository pluginPackageRepository;
    @Autowired
    PluginConfigRepository pluginConfigRepository;
    @Autowired
    ResourceServerRepository resourceServerRepository;
    @Autowired
    PluginMysqlInstanceRepository pluginMysqlInstanceRepository;

    @Autowired
    private S3Client s3Client;
    @Autowired
    private ScpService scpService;
    @Autowired
    private CommandService commandService;
    @Autowired
    private ResourceProperties resourceProperties;
    @Autowired
    GatewayServiceStub gatewayServiceStub;
    @Autowired
    SystemVariableService systemVariableService;

    @Autowired
    private ResourceManagementService resourceManagementService;
    @Autowired
    private ResourceItemRepository resourceItemRepository;

    private static final int PLUGIN_DEFAULT_START_PORT = 20000;
    private static final int PLUGIN_DEFAULT_END_PORT = 30000;

    public List<String> getAvailableContainerHosts() {
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceServerType.DOCKER);
        List<String> hostList = new ArrayList<String>();
        resourceManagementService.retrieveServers(queryRequest).getContents().forEach(rs -> {
            hostList.add(rs.getHost());
        });
        return hostList;
    }

    public Integer getAvailablePortByHostIp(String hostIp) {
        if (!(StringUtilsEx.isValidIp(hostIp))) {
            throw new RuntimeException("Invalid host ip");
        }
        ResourceServer resourceServer = resourceServerRepository
                .findByHostAndType(hostIp, ResourceServerType.DOCKER.getCode()).get(0);
        if (null == resourceServer)
            throw new WecubeCoreException(String.format("Host IP [%s] is not found", hostIp));
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceItemType.DOCKER_CONTAINER)
                .addEqualsFilter("resourceServerId", resourceServer.getId());

        List<Integer> hasUsedPorts = Lists.newArrayList();
        resourceManagementService.retrieveItems(queryRequest).getContents().forEach(rs -> {
            Arrays.asList(rs.getAdditionalPropertiesMap().get("portBindings").split(",")).forEach(port -> {
                String[] portArray = port.split(":");
                hasUsedPorts.add(Integer.valueOf(portArray[0]));
            });
        });
        if (hasUsedPorts.size() == 0) {
            return PLUGIN_DEFAULT_START_PORT;
        }

        for (int i = PLUGIN_DEFAULT_START_PORT; i < PLUGIN_DEFAULT_END_PORT; i++) {
            if (!hasUsedPorts.contains(i)) {
                return i;
            }
        }
        throw new WecubeCoreException("There is no available ports in specified host");
    }

    public List<PluginInstance> getAllInstances() {
        return Lists.newArrayList(pluginInstanceRepository.findAll());
    }

    public List<PluginInstance> getAvailableInstancesByPackageId(String packageId) {
        return pluginInstanceRepository
                .findByContainerStatusAndPluginPackage_Id(PluginInstance.CONTAINER_STATUS_RUNNING, packageId);
    }

    public PluginInstance getRunningPluginInstance(String pluginName) {
        List<PluginInstance> instances = getRunningPluginInstances(pluginName);
        if (instances.size() > 0) {
            return instances.get(0);
        }
        return null;
    }

    public List<PluginInstance> getRunningPluginInstances(String pluginName) {
        List<PluginPackage> activePluginPackages = pluginPackageRepository.findLatestActiveVersionPluginPackagesByName(pluginName);
        if (activePluginPackages == null || activePluginPackages.isEmpty()) {
            throw new WecubeCoreException(String.format("Plugin package [%s] not found.", pluginName));
        }
        
        List<PluginInstance> runningInstances = new ArrayList<PluginInstance>();
        for(PluginPackage pkg : activePluginPackages) {
        	List<PluginInstance> instances = pluginInstanceRepository
                    .findByContainerStatusAndPluginPackage_Id(PluginInstance.CONTAINER_STATUS_RUNNING, pkg.getId());
        	if(instances != null && (!instances.isEmpty())) {
        		runningInstances.addAll(instances);
        	}
        	
        	if(runningInstances.size() > 0) {
        		break;
        	}
        }

        
        if (runningInstances.isEmpty()) {
            throw new WecubeCoreException(String.format("No instance for plugin [%s] is available.", pluginName));
        }
        return runningInstances;
    }

    private boolean isContainerHostValid(String hostIp) {
        if (StringUtilsEx.isValidIp(hostIp) && isHostIpAvailable(hostIp)) {
            return true;
        }
        return false;
    }

    private boolean isPortValid(String hostIp, Integer port) {
        List<PluginInstance> pluginInstances = pluginInstanceRepository.findByHostAndPortAndContainerStatus(hostIp,
                port, PluginInstance.CONTAINER_STATUS_RUNNING);
        if (pluginInstances.size() == 0 || null == pluginInstances) {
            return true;
        }
        return false;
    }

    private String genRandomPassword() {
        String md5String = DigestUtils.md5Hex(String.valueOf(System.currentTimeMillis()));
        return md5String.length() > 16 ? md5String.substring(0, 16) : md5String;
    }

    private void validateLauchPluginInstanceParameters(PluginPackage pluginPackage, String hostIp, Integer port)
            throws Exception {
        if (!isContainerHostValid(hostIp))
            throw new WecubeCoreException("Unavailable container host ip");

        if (!isPortValid(hostIp, port))
            throw new IllegalArgumentException(String.format(
                    "The port[%d] of host[%s] is already in used, please try to reassignment port", port, hostIp));

        if (pluginPackage.getStatus().equals(PluginPackage.Status.DECOMMISSIONED)
                || pluginPackage.getStatus().equals(PluginPackage.Status.UNREGISTERED))
            throw new WecubeCoreException("'DECOMMISSIONED' or 'UNREGISTERED' state can not launch plugin instance ");
    }

    private String replaceAllocatePort(String str, Integer allocatePort) {
        return str.replace("{{ALLOCATE_PORT}}", String.valueOf(allocatePort));
    }

    private String replaceHostIp(String str, String ip) {
        return str.replace("{{ALLOCATE_HOST}}", ip);
    }

    private String replaceBaseMountPath(String baseMountPathString) {
        return systemVariableService.variableReplacement(null, baseMountPathString);
    }

    private String replaceSystemVariablesForEnvVariables(String packageName, String str) {
        return systemVariableService.variableReplacement(packageName, str);
    }

    private DatabaseInfo handleCreateDatabase(Set<PluginPackageRuntimeResourcesMysql> mysqlInfoSet,
            PluginPackage pluginPackage) {
        if (mysqlInfoSet.size() == 0) {
            return null;
        }
        if (mysqlInfoSet.size() > 1) {
            logger.error(String.format("Apply [%d] schema is not allow", mysqlInfoSet.size()));
            throw new WecubeCoreException("Only allow to plugin apply one s3 bucket so far");
        }

        List<PluginMysqlInstance> mysqlInstances = pluginMysqlInstanceRepository.findByStatusAndPluginPackage_name(
                PluginMysqlInstance.MYSQL_INSTANCE_STATUS_ACTIVE, pluginPackage.getName());
        if (mysqlInstances.size() > 0) {
            PluginMysqlInstance mysqlInstance = mysqlInstances.get(0);
            tryUpgradeMysqlDatabaseData(mysqlInstance, pluginPackage);
            mysqlInstance.setLatestUpgradeVersion(pluginPackage.getVersion());
            mysqlInstance.setUpdatedTime(new Date());
            pluginMysqlInstanceRepository.save(mysqlInstance);
            ResourceServer resourceServer = mysqlInstance.getResourceItem().getResourceServer();
            return new DatabaseInfo(resourceServer.getHost(), resourceServer.getPort(), mysqlInstance.getSchemaName(),
                    mysqlInstance.getUsername(), mysqlInstance.getPassword(), mysqlInstance.getResourceItemId());
        }

        return initMysqlDatabaseSchema(mysqlInfoSet, pluginPackage);
    }

    private void tryUpgradeMysqlDatabaseData(PluginMysqlInstance mysqlInstance, PluginPackage pluginPackage) {
        String latestVersion = mysqlInstance.getLatestUpgradeVersion();
        if (!shouldUpgradeMysqlDatabaseData(latestVersion, pluginPackage.getVersion())) {
            logger.info("latest version {} and current version {}, no need to upgrade.", latestVersion,
                    pluginPackage.getVersion());
            return;
        }

        if (isStringBlank(latestVersion)) {
            latestVersion = pluginPackage.getVersion();
        }

        try {
        	logger.info("try to perform database upgrade for {} {}", pluginPackage.getName(), pluginPackage.getVersion());
            performUpgradeMysqlDatabaseData(mysqlInstance, pluginPackage, latestVersion);
        } catch (IOException e) {
            logger.error("errors while processing upgrade sql", e);
            throw new WecubeCoreException("System error to upgrade plugin database.");
        }
    }

    private void performUpgradeMysqlDatabaseData(PluginMysqlInstance mysqlInstance, PluginPackage pluginPackage,
            String latestVersion) throws IOException {
        String tmpFolderName = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        String baseTmpDir = SystemUtils.getTempFolderPath() + tmpFolderName + "/";
        String initSqlPath = baseTmpDir + pluginProperties.getInitDbSql();

        String s3KeyName = pluginPackage.getName() + File.separator + pluginPackage.getVersion() + File.separator
                + pluginProperties.getInitDbSql();
        logger.info("Download init.sql from S3: {}", s3KeyName);

        s3Client.downFile(pluginProperties.getPluginPackageBucketName(), s3KeyName, initSqlPath);

        ResourceServer dbServer = resourceItemRepository.findById(mysqlInstance.getResourceItemId()).get()
                .getResourceServer();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://" + dbServer.getHost() + ":" + dbServer.getPort() + "/" + mysqlInstance.getSchemaName()
                        + "?characterEncoding=utf8&serverTimezone=UTC",
                mysqlInstance.getUsername(), EncryptionUtils.decryptWithAes(mysqlInstance.getPassword(),
                        resourceProperties.getPasswordEncryptionSeed(), mysqlInstance.getSchemaName()));
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");

        File initSqlFile = new File(initSqlPath);

        File upgradeSqlFile = parseUpgradeMysqlDataFile(baseTmpDir, initSqlFile, pluginPackage, latestVersion);
        List<Resource> scripts = newArrayList(new FileSystemResource(upgradeSqlFile));
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(false);
        populator.setSeparator(";");
        populator.setCommentPrefix("#");
        populator.setSqlScriptEncoding("utf-8");
        for(Resource script : scripts) {
        	populator.addScript(script);
        }
        try {
        	logger.info("start to execute sql script file:{}, host:{},port:{},schema:{}", 
        			upgradeSqlFile.getAbsolutePath(),
        			dbServer.getHost(),
        			dbServer.getPort(),
        			mysqlInstance.getSchemaName());
            populator.execute(dataSource);
        } catch (Exception e) {
            String errorMessage = String.format("Failed to execute [{}] for schema[%s]",
            		upgradeSqlFile.getName(), mysqlInstance.getSchemaName());
            logger.error(errorMessage);
            throw new WecubeCoreException(errorMessage, e);
        }
        logger.info(String.format("Upgrade database[%s] finished...", mysqlInstance.getSchemaName()));
    }

    private File parseUpgradeMysqlDataFile(String baseTmpDir, File initSqlFile, PluginPackage pluginPackage,
            String latestVersion) throws IOException {
        File upgradeSqlFile = new File(baseTmpDir, String.format("upgrade%s.sql", System.currentTimeMillis()));
        Pattern p = Pattern.compile(VersionTagInfo.VERSION_TAG_PATTERN);
        String foreignCheckOff = "SET FOREIGN_KEY_CHECKS = 0;";
        String foreignCheckOn = "SET FOREIGN_KEY_CHECKS = 1;";

        BufferedReader br = null;
        BufferedWriter bw = null;
        String currentVersion = pluginPackage.getVersion();
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(initSqlFile), Charset.forName("utf-8")));
            bw = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(upgradeSqlFile), Charset.forName("utf-8")));

            bw.write(foreignCheckOff+"\n");
            long lineNum = 0L;
            String sLine = null;
            boolean shouldStart = false;
            boolean shouldStop = false;
            while ((sLine = br.readLine()) != null) {
                lineNum++;
                String trimLine = sLine.trim();
                Matcher m = p.matcher(trimLine);
                if (m.matches()) {
                    VersionTagInfo info = VersionTagInfo.parseVersionTagInfo(m, lineNum);
                    if (!shouldStart) {
                        if (shouldStart(info, latestVersion, currentVersion)) {
                            shouldStart = true;
                        }
                    }

                    if (!shouldStop) {
                        if (shouldStop(info, currentVersion)) {
                            shouldStop = true;
                        }
                    }
                }

                if (shouldStart) {
                    bw.write(sLine + "\n");
                }

                if (shouldStop) {
                    break;
                }
            }
            
            bw.write(foreignCheckOn+"\n");
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    logger.warn("", e);
                }
            }

            if (bw != null) {
                try {
                    bw.close();
                } catch (IOException e) {
                    logger.warn("", e);
                }
            }
        }
        return upgradeSqlFile;
    }

    private boolean shouldStart(VersionTagInfo info, String latestVersion, String currentVersion) {
        if (!info.isBegin()) {
            return false;
        }
        if (versionGreaterThan(info.getVersion(), latestVersion)) {
            return true;
        }

        if (versionEquals(info.getVersion(), currentVersion)) {
            return true;
        }

        return false;
    }

    private boolean shouldStop(VersionTagInfo info, String currentVersion) {

        if (versionGreaterThan(info.getVersion(), currentVersion)) {
            return true;
        }

        if (versionEquals(info.getVersion(), currentVersion)) {
            if (info.isEnd()) {
                return true;
            }
        }

        return false;
    }

    public boolean versionGreaterThan(String version, String baseVersion) {
        VersionComparator vc = new VersionComparator();
        int compare = vc.compare(version, baseVersion);
        if (compare > 0) {
            return true;
        }

        return false;

    }

    public boolean versionEquals(String version, String baseVersion) {
        VersionComparator vc = new VersionComparator();
        int compare = vc.compare(version, baseVersion);
        if (compare == 0) {
            return true;
        }

        return false;
    }

    private boolean isStringBlank(String s) {
        if (s == null || s.trim().length() < 1) {
            return true;
        }

        return false;
    }

    private boolean shouldUpgradeMysqlDatabaseData(String latestVersion, String currentVersion) {
        if (isStringBlank(latestVersion)) {
            return true;
        }
        
        if(versionEquals(currentVersion, latestVersion)){
            return false;
        }
        
        if(versionGreaterThan(currentVersion, latestVersion)){
            return true;
        }

        return false;
    }

    private String handleCreateS3Bucket(Set<PluginPackageRuntimeResourcesS3> s3InfoSet, PluginPackage pluginPackage) {
        if (s3InfoSet.size() == 0) {
            return null;
        }
        if (s3InfoSet.size() > 1) {
            logger.error(String.format("Apply [%d] s3 buckets is not allow", s3InfoSet.size()));
            throw new WecubeCoreException(String.format("Apply [%d] s3 buckets is not allow", s3InfoSet.size()));
        }

        List<ResourceItem> s3BucketsItems = resourceItemRepository
                .findByNameAndType(s3InfoSet.iterator().next().getBucketName(), ResourceItemType.S3_BUCKET.toString());
        if (s3BucketsItems.size() > 0) {
            return s3BucketsItems.get(0).getId();
        } else {
            return initS3BucketResource(s3InfoSet);
        }
    }

    public void launchPluginInstance(String packageId, String hostIp, Integer port)
            throws Exception, WecubeCoreException {
        Optional<PluginPackage> pluginPackageResult = pluginPackageRepository.findById(packageId);
        if (!pluginPackageResult.isPresent())
            throw new WecubeCoreException("Plugin package id does not exist, id = " + packageId);

        PluginPackage pluginPackage = pluginPackageResult.get();
        validateLauchPluginInstanceParameters(pluginPackage, hostIp, port);

        Set<PluginPackageRuntimeResourcesDocker> dockerInfoSet = pluginPackage.getPluginPackageRuntimeResourcesDocker();
        Set<PluginPackageRuntimeResourcesMysql> mysqlInfoSet = pluginPackage.getPluginPackageRuntimeResourcesMysql();
        Set<PluginPackageRuntimeResourcesS3> s3InfoSet = pluginPackage.getPluginPackageRuntimeResourcesS3();

        PluginInstance instance = new PluginInstance();
        instance.setPluginPackage(pluginPackage);

        DatabaseInfo dbInfo = handleCreateDatabase(mysqlInfoSet, pluginPackage);
        if (dbInfo != null)
            instance.setPluginMysqlInstanceResourceId(dbInfo.getResourceItemId());

        String s3BucketResourceId = handleCreateS3Bucket(s3InfoSet, pluginPackage);
        if (s3BucketResourceId != null)
            instance.setS3BucketResourceId(s3BucketResourceId);

        // 3. create docker instance
        if (dockerInfoSet.size() != 1) {
            throw new WecubeCoreException("Only support plugin running in one container so far");
        }
        PluginPackageRuntimeResourcesDocker dockerInfo = dockerInfoSet.iterator().next();

        String portBindingString = replaceAllocatePort(dockerInfo.getPortBindings(), port);
        String envVariablesString = replaceHostIp(dockerInfo.getEnvVariables(), hostIp);
        String volumeBindingString = replaceBaseMountPath(dockerInfo.getVolumeBindings());

        CreateInstanceDto createContainerParameters = new CreateInstanceDto(dockerInfo.getImageName(),
                dockerInfo.getContainerName(), portBindingString, volumeBindingString);

        envVariablesString = envVariablesString.replace(",", "\\,");
        if (mysqlInfoSet.size() != 0) {
            envVariablesString = envVariablesString.replace("{{DB_HOST}}", dbInfo.getHost())
                    .replace("{{DB_PORT}}", dbInfo.getPort()).replace("{{DB_SCHEMA}}", dbInfo.getSchema())
                    .replace("{{DB_USER}}", dbInfo.getUser()).replace("{{DB_PWD}}", EncryptionUtils.decryptWithAes(
                            dbInfo.getPassword(), resourceProperties.getPasswordEncryptionSeed(), dbInfo.getSchema()));
        }
        logger.info("before replace envVariablesString=" + envVariablesString);
        envVariablesString = replaceSystemVariablesForEnvVariables(pluginPackage.getName(), envVariablesString);
        logger.info("after replace envVariablesString=" + envVariablesString);

        createContainerParameters.setEnvVariableParameters(envVariablesString.isEmpty() ? "" : envVariablesString);

        try {
            ResourceItemDto dockerResourceDto = createPluginDockerInstance(pluginPackage, hostIp,
                    createContainerParameters);
            instance.setDockerInstanceResourceId(dockerResourceDto.getId());
        } catch (Exception e) {
            logger.error("Creating docker container instance meet error: ", e.getMessage());
            throw new WecubeCoreException("Creating docker container instance meet error: " + e.getMessage(), e);
        }

        instance.setContainerName(dockerInfo.getContainerName());
        instance.setInstanceName(pluginPackage.getName());
        instance.setHost(hostIp);
        instance.setPort(port);

        // 4. insert to DB
        instance.setContainerStatus(PluginInstance.CONTAINER_STATUS_RUNNING);
        pluginInstanceRepository.save(instance);

        // 6. register route
        GatewayResponse response = registerRoute(pluginPackage.getName(), hostIp, String.valueOf(port));
        if (!response.getStatus().equals(GatewayResponse.getStatusCodeOk())) {
            logger.error("Launch instance has done, but register routing information is failed, please check");
        }
    }

    private DatabaseInfo initMysqlDatabaseSchema(Set<PluginPackageRuntimeResourcesMysql> mysqlSet,
            PluginPackage pluginPackage) {
        if (mysqlSet.size() != 0) {
            PluginMysqlInstance mysqlInstance = createPluginMysqlDatabase(mysqlSet.iterator().next(),
                    pluginPackage.getVersion());

            ResourceServer dbServer = resourceItemRepository.findById(mysqlInstance.getResourceItemId()).get()
                    .getResourceServer();
            DatabaseInfo dbInfo = new DatabaseInfo(dbServer.getHost(), dbServer.getPort(),
                    mysqlInstance.getSchemaName(), mysqlInstance.getUsername(), mysqlInstance.getPassword(),
                    mysqlInstance.getId());

            // execute init.sql
            initMysqlDatabaseTables(dbServer, mysqlInstance, pluginPackage);
            return dbInfo;
        }
        return null;
    }

    private void initMysqlDatabaseTables(ResourceServer dbServer, PluginMysqlInstance mysqlInstance,
            PluginPackage pluginPackage) {

        String tmpFolderName = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        String initSqlPath = SystemUtils.getTempFolderPath() + tmpFolderName + "/" + pluginProperties.getInitDbSql();

        String s3KeyName = pluginPackage.getName() + File.separator + pluginPackage.getVersion() + File.separator
                + pluginProperties.getInitDbSql();
        logger.info("Download init.sql from S3: {}", s3KeyName);

        s3Client.downFile(pluginProperties.getPluginPackageBucketName(), s3KeyName, initSqlPath);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:mysql://" + dbServer.getHost() + ":" + dbServer.getPort() + "/" + mysqlInstance.getSchemaName()
                        + "?characterEncoding=utf8&serverTimezone=UTC",
                mysqlInstance.getUsername(), EncryptionUtils.decryptWithAes(mysqlInstance.getPassword(),
                        resourceProperties.getPasswordEncryptionSeed(), mysqlInstance.getSchemaName()));
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        File initSqlFile = new File(initSqlPath);
        List<Resource> scipts = newArrayList(new FileSystemResource(initSqlFile));
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(false);
        populator.setSeparator(";");
        scipts.forEach(populator::addScript);
        try {
            populator.execute(dataSource);
        } catch (Exception e) {
            String errorMessage = String.format("Failed to execute init.sql for schema[%s]",
                    mysqlInstance.getSchemaName());
            logger.error(errorMessage);
            throw new WecubeCoreException(errorMessage, e);
        }
        logger.info(String.format("Init database[%s] tables has done..", mysqlInstance.getSchemaName()));
    }

    private String initS3BucketResource(Set<PluginPackageRuntimeResourcesS3> s3InfoSet) {
        if (s3InfoSet.size() > 1) {
            logger.error(String.format("Apply [%d] s3 bucket is not allow", s3InfoSet.size()));
            throw new WecubeCoreException("Only allow to plugin apply one s3 bucket");
        }
        return createPluginS3Bucket(s3InfoSet.iterator().next());
    }

    public PluginMysqlInstance createPluginMysqlDatabase(PluginPackageRuntimeResourcesMysql mysqlInfo,
            String currentPluginVersion) {
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceServerType.MYSQL);
        List<ResourceServerDto> mysqlServers = resourceManagementService.retrieveServers(queryRequest).getContents();
        if (mysqlServers.size() == 0) {
            throw new WecubeCoreException("Can not found available resource server for creating mysql database");
        }
        ResourceServerDto mysqlServer = mysqlServers.get(0);

        String dbPassword = genRandomPassword();
        String dbUser = mysqlInfo.getSchemaName();

        ResourceItemDto createMysqlDto = new ResourceItemDto(mysqlInfo.getSchemaName(),
                ResourceItemType.MYSQL_DATABASE.getCode(),
                buildAdditionalPropertiesForMysqlDatabase(dbUser.length() > 16 ? dbUser.substring(0, 16) : dbUser,
                        dbPassword),
                mysqlServer.getId(), String.format("Create MySQL database for plugin[%s]", mysqlInfo.getSchemaName()));
        mysqlServer.setResourceItemDtos(null);
        createMysqlDto.setResourceServer(mysqlServer);
        createMysqlDto.setIsAllocated(true);
        logger.info("Mysql Database schema creating...");
        if (logger.isDebugEnabled()) {
            logger.info("Request parameters= " + createMysqlDto);
        }

        List<ResourceItemDto> result = resourceManagementService.createItems(Lists.newArrayList(createMysqlDto));
        PluginMysqlInstance mysqlInstance = new PluginMysqlInstance(mysqlInfo.getSchemaName(), result.get(0).getId(),
                mysqlInfo.getSchemaName(),
                EncryptionUtils.encryptWithAes(dbPassword, resourceProperties.getPasswordEncryptionSeed(),
                        mysqlInfo.getSchemaName()),
                PluginMysqlInstance.MYSQL_INSTANCE_STATUS_ACTIVE, mysqlInfo.getPluginPackage());
        mysqlInstance.setLatestUpgradeVersion(currentPluginVersion);
        mysqlInstance.setCreatedTime(new Date());
        pluginMysqlInstanceRepository.save(mysqlInstance);

        logger.info("Mysql Database schema creation has done...");
        return mysqlInstance;
    }

    private String createPluginS3Bucket(PluginPackageRuntimeResourcesS3 s3Info) {
        QueryRequest queryRequest = QueryRequest.defaultQueryObject("type", ResourceServerType.S3);
        List<ResourceServerDto> s3Servers = resourceManagementService.retrieveServers(queryRequest).getContents();
        if (s3Servers.size() == 0) {
            throw new WecubeCoreException("Can not found available resource server for creating s3 bucket");
        }
        ResourceServerDto s3Server = s3Servers.get(0);
        ResourceItemDto createS3BucketDto = new ResourceItemDto(s3Info.getBucketName(),
                ResourceItemType.S3_BUCKET.getCode(), null, s3Server.getId(),
                String.format("Create S3 bucket for plugin[%s]", s3Info.getBucketName()));
        createS3BucketDto.setResourceServer(s3Server);
        createS3BucketDto.setIsAllocated(true);
        logger.info("S3 bucket creating...");
        if (logger.isDebugEnabled())
            logger.info("Request parameters= " + createS3BucketDto);

        List<ResourceItemDto> result = resourceManagementService.createItems(Lists.newArrayList(createS3BucketDto));

        logger.info("S3 bucket creation has done...");
        return result.get(0).getId();
    }

    private ResourceItemDto createPluginDockerInstance(PluginPackage pluginPackage, String hostIp,
            CreateInstanceDto createContainerParameters) throws Exception {
        ResourceServer hostInfo = null;
        List<ResourceServer> hostInfos = resourceServerRepository.findByHostAndType(hostIp,
                ResourceServerType.DOCKER.getCode());
        if (hostInfos.size() == 0) {
            logger.info(String.format("Can not found docker resource server by IP[%s]", hostIp));
            throw new WecubeCoreException(String.format("Can not found docker resource server by IP[%s]", hostIp));
        }
        hostInfo = hostInfos.get(0);

        // download package from MinIO
        String tmpFolderName = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
        String tmpFilePath = SystemUtils.getTempFolderPath() + tmpFolderName + "/" + pluginProperties.getImageFile();

        String s3KeyName = pluginPackage.getName() + File.separator + pluginPackage.getVersion() + File.separator
                + pluginProperties.getImageFile();
        logger.info("Download plugin package from S3: {}", s3KeyName);

        s3Client.downFile(pluginProperties.getPluginPackageBucketName(), s3KeyName, tmpFilePath);

        logger.info("scp from local:{} to remote: {}", tmpFilePath, pluginProperties.getPluginDeployPath());
        try {
            scpService.put(hostIp, Integer.valueOf(hostInfo.getPort()), hostInfo.getLoginUsername(),
                    EncryptionUtils.decryptWithAes(hostInfo.getLoginPassword(),
                            resourceProperties.getPasswordEncryptionSeed(), hostInfo.getName()),
                    tmpFilePath, pluginProperties.getPluginDeployPath());
        } catch (Exception e) {
            logger.error("Put file to remote host meet error: {}", e.getMessage());
            throw new WecubeCoreException("Put file to remote host meet error: " + e.getMessage(), e);
        }

        // load image at remote host
        String loadCmd = "docker load -i " + pluginProperties.getPluginDeployPath().trim() + File.separator
                + pluginProperties.getImageFile();
        logger.info("Run docker load command: " + loadCmd);
        try {
            commandService.runAtRemote(hostIp, hostInfo.getLoginUsername(),
                    EncryptionUtils.decryptWithAes(hostInfo.getLoginPassword(),
                            resourceProperties.getPasswordEncryptionSeed(), hostInfo.getName()),
                    Integer.valueOf(hostInfo.getPort()), loadCmd);
        } catch (Exception e) {
            logger.error("Run command [{}] meet error: {}", loadCmd, e.getMessage());
            throw new WecubeCoreException(String.format("Run remote command meet error: %s", e.getMessage()), e);
        }

        ResourceItemDto createDockerInstanceDto = new ResourceItemDto(createContainerParameters.getContainerName(),
                ResourceItemType.DOCKER_CONTAINER.getCode(),
                buildAdditionalPropertiesForDocker(createContainerParameters), hostInfo.getId(),
                String.format("Create docker instance for plugin[%s]", pluginPackage.getName()));
        createDockerInstanceDto.setIsAllocated(true);
        logger.info("Container creating...");
        logger.info("Request parameters= " + createDockerInstanceDto.toString());

        List<ResourceItemDto> result = resourceManagementService
                .createItems(Lists.newArrayList(createDockerInstanceDto));

        logger.info("Container creation has done...");
        return result.get(0);
    }

    public void removePluginInstanceById(String instanceId) throws Exception {
        logger.info("Removing instanceId: " + instanceId);
        Optional<PluginInstance> instanceOptional = pluginInstanceRepository.findById(instanceId);
        PluginInstance instance = instanceOptional.get();
        ResourceItemDto removeDockerInstanceDto = new ResourceItemDto();
        removeDockerInstanceDto.setName(instance.getContainerName());
        removeDockerInstanceDto.setId(instance.getDockerInstanceResourceId());
        resourceManagementService.deleteItems(Lists.newArrayList(removeDockerInstanceDto));
        pluginInstanceRepository.deleteById(instanceId);
    }

    private boolean isHostIpAvailable(String hostIp) {
        if (getAvailableContainerHosts().contains(hostIp))
            return true;
        return false;
    }

    private String buildAdditionalPropertiesForMysqlDatabase(String username, String password) {
        HashMap<String, String> additionalProperties = new HashMap<String, String>();
        additionalProperties.put("username", username);
        additionalProperties.put("password", password);
        return JsonUtils.toJsonString(additionalProperties);
    }

    private String buildAdditionalPropertiesForDocker(CreateInstanceDto createContainerParameters) {
        HashMap<String, String> additionalProperties = new HashMap<String, String>();
        additionalProperties.put("imageName", createContainerParameters.getImageName());
        additionalProperties.put("portBindings", createContainerParameters.getPortBindingParameters());
        additionalProperties.put("volumeBindings", createContainerParameters.getVolumeBindingParameters());
        additionalProperties.put("envVariables", createContainerParameters.getEnvVariableParameters());

        return JsonUtils.toJsonString(additionalProperties);
    }

    public String getInstanceAddress(PluginInstance instance) {
        return trim(instance.getHost()) + ":" + trim(instance.getPort().toString());
    }

    private class DatabaseInfo {
        String host;
        String port;
        String schema;
        String user;
        String password;
        String resourceItemId;

        private DatabaseInfo(String host, String port, String schema, String user, String password,
                String resourceItemId) {
            this.host = host;
            this.port = port;
            this.schema = schema;
            this.user = user;
            this.password = password;
            this.resourceItemId = resourceItemId;
        }

        public DatabaseInfo() {
        }

        public String getUser() {
            return user;
        }

        public String getPassword() {
            return password;
        }

        public String getHost() {
            return host;
        }

        public String getPort() {
            return port;
        }

        public String getSchema() {
            return schema;
        }

        public String getResourceItemId() {
            return resourceItemId;
        }
    }

    private GatewayResponse registerRoute(String name, String host, String port) {
        return gatewayServiceStub.registerRoute(
                new RegisterRouteItemsDto(name, Lists.newArrayList(new RouteItem(name, "http", host, port))));
    }
}
