package com.hubspot.singularity.data.history;

import java.util.List;
import java.util.Map;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.SingularityDeployHistory;
import com.hubspot.singularity.SingularityDeployKey;
import com.hubspot.singularity.SingularityRequestDeployState;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DeployManager;

@Singleton
public class SingularityDeployHistoryPersister extends SingularityHistoryPersister {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityDeployHistoryPersister.class);

  private final DeployManager deployManager;
  private final HistoryManager historyManager;

  @Inject
  public SingularityDeployHistoryPersister(SingularityConfiguration configuration, DeployManager deployManager, HistoryManager historyManager) {
    super(configuration);

    this.deployManager = deployManager;
    this.historyManager = historyManager;
  }

  @Override
  public void runActionOnPoll() {
    LOG.info("Checking inactive deploys for deploy history persistance");

    final long start = System.currentTimeMillis();

    final List<SingularityDeployKey> allDeployIds = deployManager.getAllDeployIds();
    final Map<String, SingularityRequestDeployState> byRequestId = deployManager.getAllRequestDeployStatesByRequestId();

    int numTotal = 0;
    int numTransferred = 0;

    for (SingularityDeployKey deployKey : allDeployIds) {
      SingularityRequestDeployState deployState = byRequestId.get(deployKey.getRequestId());

      if (!shouldTransferDeploy(deployState, deployKey)) {
        continue;
      }

      if (transferToHistoryDB(deployKey)) {
        numTransferred++;
      }

      numTotal++;
    }

    LOG.info("Transferred {} out of {} deploys in {}", numTransferred, numTotal, JavaUtils.duration(start));
  }

  private boolean shouldTransferDeploy(SingularityRequestDeployState deployState, SingularityDeployKey deployKey) {
    if (deployState == null) {
      LOG.warn("Missing request deploy state for deployKey {}", deployKey);
      return true;
    }

    if (deployState.getActiveDeploy().isPresent() && deployState.getActiveDeploy().get().getDeployId().equals(deployKey.getDeployId())) {
      return false;
    }

    if (deployState.getPendingDeploy().isPresent() && deployState.getPendingDeploy().get().getDeployId().equals(deployKey.getDeployId())) {
      return false;
    }

    return true;
  }

  private boolean transferToHistoryDB(SingularityDeployKey deployKey) {
    final long start = System.currentTimeMillis();

    Optional<SingularityDeployHistory> deployHistory = deployManager.getDeployHistory(deployKey.getRequestId(), deployKey.getDeployId(), true);

    if (!deployHistory.isPresent()) {
      LOG.info("Deploy history for key {} not found", deployKey);
      return false;
    }

    try {
      historyManager.saveDeployHistory(deployHistory.get());
    } catch (Throwable t) {
      LOG.warn("Failed to persist deploy history {} into History for deploy {}", deployHistory.get(), deployKey, t);
      return false;
    }

    deployManager.deleteDeployHistory(deployKey);

    LOG.debug("Moved deploy history for {} from ZK to History in {}", deployKey, JavaUtils.duration(start));

    return true;
  }

}
