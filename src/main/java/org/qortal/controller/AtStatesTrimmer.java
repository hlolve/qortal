package org.qortal.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.data.block.BlockData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.NTP;

public class AtStatesTrimmer implements Runnable {

	private static final Logger LOGGER = LogManager.getLogger(AtStatesTrimmer.class);

	@Override
	public void run() {
		Thread.currentThread().setName("AT States trimmer");

		try (final Repository repository = RepositoryManager.getRepository()) {
			repository.getATRepository().prepareForAtStateTrimming();
			repository.saveChanges();

			while (!Controller.isStopping()) {
				repository.discardChanges();

				Thread.sleep(Settings.getInstance().getAtStatesTrimInterval());

				BlockData chainTip = Controller.getInstance().getChainTip();
				if (chainTip == null || NTP.getTime() == null)
					continue;

				// Don't even attempt if we're mid-sync as our repository requests will be delayed for ages
				if (Controller.getInstance().isSynchronizing())
					continue;

				long currentTrimmableTimestamp = NTP.getTime() - Settings.getInstance().getAtStatesMaxLifetime();
				// We want to keep AT states near the tip of our copy of blockchain so we can process/orphan nearby blocks
				long chainTrimmableTimestamp = chainTip.getTimestamp() - Settings.getInstance().getAtStatesMaxLifetime();

				long upperTrimmableTimestamp = Math.min(currentTrimmableTimestamp, chainTrimmableTimestamp);
				int upperTrimmableHeight = repository.getBlockRepository().getHeightFromTimestamp(upperTrimmableTimestamp);

				int trimStartHeight = repository.getATRepository().getAtTrimHeight();

				int upperBatchHeight = trimStartHeight + Settings.getInstance().getAtStatesTrimBatchSize();
				int upperTrimHeight = Math.min(upperBatchHeight, upperTrimmableHeight);

				if (trimStartHeight >= upperTrimHeight)
					continue;

				int numAtStatesTrimmed = repository.getATRepository().trimAtStates(trimStartHeight, upperTrimHeight, Settings.getInstance().getAtStatesTrimLimit());
				repository.saveChanges();

				if (numAtStatesTrimmed > 0) {
					LOGGER.debug(() -> String.format("Trimmed %d AT state%s between blocks %d and %d",
							numAtStatesTrimmed, (numAtStatesTrimmed != 1 ? "s" : ""),
							trimStartHeight, upperTrimHeight));
				} else {
					// Can we move onto next batch?
					if (upperTrimmableHeight > upperBatchHeight) {
						repository.getATRepository().setAtTrimHeight(upperBatchHeight);
						repository.getATRepository().prepareForAtStateTrimming();
						repository.saveChanges();

						LOGGER.debug(() -> String.format("Bumping AT state trim height to %d", upperBatchHeight));
					}
				}
			}
		} catch (DataException e) {
			LOGGER.warn(String.format("Repository issue trying to trim AT states: %s", e.getMessage()));
		} catch (InterruptedException e) {
			// Time to exit
		}
	}

}