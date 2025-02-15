package org.qortal.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortal.data.network.TradePresenceData;
import org.qortal.transform.Transformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * For requesting trade presences from remote peer, given our list of known trade presences.
 *
 * Groups of: number of entries, timestamp, then AT trade pubkey for each entry.
 */
public class GetTradePresencesMessage extends Message {
	private List<TradePresenceData> tradePresences;
	private byte[] cachedData;

	public GetTradePresencesMessage(List<TradePresenceData> tradePresences) {
		this(-1, tradePresences);
	}

	private GetTradePresencesMessage(int id, List<TradePresenceData> tradePresences) {
		super(id, MessageType.GET_TRADE_PRESENCES);

		this.tradePresences = tradePresences;
	}

	public List<TradePresenceData> getTradePresences() {
		return this.tradePresences;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		int groupedEntriesCount = bytes.getInt();

		List<TradePresenceData> tradePresences = new ArrayList<>(groupedEntriesCount);

		while (groupedEntriesCount > 0) {
			long timestamp = bytes.getLong();

			for (int i = 0; i < groupedEntriesCount; ++i) {
				byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
				bytes.get(publicKey);

				tradePresences.add(new TradePresenceData(timestamp, publicKey));
			}

			if (bytes.hasRemaining()) {
				groupedEntriesCount = bytes.getInt();
			} else {
				// we've finished
				groupedEntriesCount = 0;
			}
		}

		return new GetTradePresencesMessage(id, tradePresences);
	}

	@Override
	protected synchronized byte[] toData() {
		if (this.cachedData != null)
			return this.cachedData;

		// Shortcut in case we have no trade presences
		if (this.tradePresences.isEmpty()) {
			this.cachedData = Ints.toByteArray(0);
			return this.cachedData;
		}

		// How many of each timestamp
		Map<Long, Integer> countByTimestamp = new HashMap<>();

		for (TradePresenceData tradePresenceData : this.tradePresences) {
			Long timestamp = tradePresenceData.getTimestamp();
			countByTimestamp.compute(timestamp, (k, v) -> v == null ? 1 : ++v);
		}

		// We should know exactly how many bytes to allocate now
		int byteSize = countByTimestamp.size() * (Transformer.INT_LENGTH + Transformer.TIMESTAMP_LENGTH)
				+ this.tradePresences.size() * Transformer.PUBLIC_KEY_LENGTH;

		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(byteSize);

			for (long timestamp : countByTimestamp.keySet()) {
				bytes.write(Ints.toByteArray(countByTimestamp.get(timestamp)));

				bytes.write(Longs.toByteArray(timestamp));

				for (TradePresenceData tradePresenceData : this.tradePresences) {
					if (tradePresenceData.getTimestamp() == timestamp)
						bytes.write(tradePresenceData.getPublicKey());
				}
			}

			this.cachedData = bytes.toByteArray();
			return this.cachedData;
		} catch (IOException e) {
			return null;
		}
	}

}
