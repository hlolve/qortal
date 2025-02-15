package org.qortal.network.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.qortal.data.network.OnlineAccountData;
import org.qortal.transform.Transformer;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

public class GetOnlineAccountsMessage extends Message {
	private static final int MAX_ACCOUNT_COUNT = 5000;

	private List<OnlineAccountData> onlineAccounts;

	public GetOnlineAccountsMessage(List<OnlineAccountData> onlineAccounts) {
		this(-1, onlineAccounts);
	}

	private GetOnlineAccountsMessage(int id, List<OnlineAccountData> onlineAccounts) {
		super(id, MessageType.GET_ONLINE_ACCOUNTS);

		this.onlineAccounts = onlineAccounts.stream().limit(MAX_ACCOUNT_COUNT).collect(Collectors.toList());
	}

	public List<OnlineAccountData> getOnlineAccounts() {
		return this.onlineAccounts;
	}

	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws UnsupportedEncodingException {
		final int accountCount = bytes.getInt();

		List<OnlineAccountData> onlineAccounts = new ArrayList<>(accountCount);

		for (int i = 0; i < Math.min(MAX_ACCOUNT_COUNT, accountCount); ++i) {
			long timestamp = bytes.getLong();

			byte[] publicKey = new byte[Transformer.PUBLIC_KEY_LENGTH];
			bytes.get(publicKey);

			onlineAccounts.add(new OnlineAccountData(timestamp, null, publicKey));
		}

		return new GetOnlineAccountsMessage(id, onlineAccounts);
	}

	@Override
	protected byte[] toData() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			bytes.write(Ints.toByteArray(this.onlineAccounts.size()));

			for (int i = 0; i < this.onlineAccounts.size(); ++i) {
				OnlineAccountData onlineAccountData = this.onlineAccounts.get(i);
				bytes.write(Longs.toByteArray(onlineAccountData.getTimestamp()));

				bytes.write(onlineAccountData.getPublicKey());
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			return null;
		}
	}

}
