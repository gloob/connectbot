/*
	ConnectBot: simple, powerful, open-source SSH client for Android
	Copyright (C) 2007-2008 Kenny Root, Jeffrey Sharkey

	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.connectbot.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.connectbot.R;
import org.connectbot.bean.HostBean;
import org.connectbot.bean.PortForwardBean;
import org.connectbot.bean.PubkeyBean;
import org.connectbot.service.TerminalBridge;
import org.connectbot.service.TerminalManager;
import org.connectbot.service.TerminalManager.KeyHolder;
import org.connectbot.ssh2.Channel;
import org.connectbot.ssh2.ConnectionInfo;
import org.connectbot.ssh2.NativeInputStream;
import org.connectbot.ssh2.Session;
import org.connectbot.util.HostDatabase;
import org.connectbot.util.PubkeyDatabase;
import org.connectbot.util.PubkeyUtils;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

/**
 * @author Kenny Root
 *
 */
public class SSH extends AbsTransport {
	public SSH() {
		super();
	}

	/**
	 * @param bridge
	 * @param db
	 */
	public SSH(HostBean host, TerminalBridge bridge, TerminalManager manager) {
		super(host, bridge, manager);
	}

	private static final String PROTOCOL = "ssh";
	private static final String TAG = "ConnectBot.SSH";
	private static final int DEFAULT_PORT = 22;

	private static final String AUTH_PUBLICKEY = "publickey",
		AUTH_PASSWORD = "password",
		AUTH_KEYBOARDINTERACTIVE = "keyboard-interactive";

	private final static int AUTH_TRIES = 20;

	static final Pattern hostmask;
	static {
		hostmask = Pattern.compile("^(.+)@([0-9a-z.-]+)(:(\\d+))?$", Pattern.CASE_INSENSITIVE);
	}

	private boolean compression = false;
	private volatile boolean authenticated = false;
	private volatile boolean connected = false;
	private volatile boolean sessionOpen = false;

	private boolean pubkeysExhausted = false;

	private Session connection;
	private Channel session;

	private OutputStream stdin;
	private NativeInputStream stdout;
	private NativeInputStream stderr;

	private List<PortForwardBean> portForwards = new LinkedList<PortForwardBean>();

	private int columns;
	private int rows;

	private int width;
	private int height;

	private String useAuthAgent = HostDatabase.AUTHAGENT_NO;
	private String agentLockPassphrase;

	private void authenticate() {
		bridge.outputLine(manager.res.getString(R.string.terminal_auth));

//		try {
//			long pubkeyId = host.getPubkeyId();
//
//			if (!pubkeysExhausted &&
//					pubkeyId != HostDatabase.PUBKEYID_NEVER &&
//					connection.isAuthMethodAvailable(host.getUsername(), AUTH_PUBLICKEY)) {
//
//				// if explicit pubkey defined for this host, then prompt for password as needed
//				// otherwise just try all in-memory keys held in terminalmanager
//
//				if (pubkeyId == HostDatabase.PUBKEYID_ANY) {
//					// try each of the in-memory keys
//					bridge.outputLine(manager.res
//							.getString(R.string.terminal_auth_pubkey_any));
//					for (Entry<String, KeyHolder> entry : manager.loadedKeypairs.entrySet()) {
//						if (entry.getValue().bean.isConfirmUse()
//								&& !promptForPubkeyUse(entry.getKey()))
//							continue;
//
//						if (this.tryPublicKey(host.getUsername(), entry.getKey(),
//								entry.getValue().trileadKey)) {
//							finishConnection();
//							break;
//						}
//					}
//				} else {
//					bridge.outputLine(manager.res.getString(R.string.terminal_auth_pubkey_specific));
//					// use a specific key for this host, as requested
//					PubkeyBean pubkey = manager.pubkeydb.findPubkeyById(pubkeyId);
//
//					if (pubkey == null)
//						bridge.outputLine(manager.res.getString(R.string.terminal_auth_pubkey_invalid));
//					else
//						if (tryPublicKey(pubkey))
//							finishConnection();
//				}
//
//				pubkeysExhausted = true;
//			} else if (connection.isAuthMethodAvailable(host.getUsername(), AUTH_PASSWORD)) {
				bridge.outputLine(manager.res.getString(R.string.terminal_auth_pass));
				String password = bridge.getPromptHelper().requestStringPrompt(null,
						manager.res.getString(R.string.prompt_password));
				if (password != null
						&& connection.authenticatePassword(host.getUsername(), password)) {
					finishConnection();
				} else {
					bridge.outputLine(manager.res.getString(R.string.terminal_auth_pass_fail));
				}
//			} else if(connection.isAuthMethodAvailable(host.getUsername(), AUTH_KEYBOARDINTERACTIVE)) {
//				// this auth method will talk with us using InteractiveCallback interface
//				// it blocks until authentication finishes
//				bridge.outputLine(manager.res.getString(R.string.terminal_auth_ki));
//				if(connection.authenticateWithKeyboardInteractive(host.getUsername(), this)) {
//					finishConnection();
//				} else {
//					bridge.outputLine(manager.res.getString(R.string.terminal_auth_ki_fail));
//				}
//			} else {
//				bridge.outputLine(manager.res.getString(R.string.terminal_auth_fail));
//			}
//		} catch (IllegalStateException e) {
//			Log.e(TAG, "Connection went away while we were trying to authenticate", e);
//			return;
//		} catch(Exception e) {
//			Log.e(TAG, "Problem during handleAuthentication()", e);
//		}
	}

	/**
	 * Attempt connection with database row pointed to by cursor.
	 * @param cursor
	 * @return true for successful authentication
	 * @throws NoSuchAlgorithmException
	 * @throws InvalidKeySpecException
	 * @throws IOException
	 */
	private boolean tryPublicKey(PubkeyBean pubkey) throws NoSuchAlgorithmException, InvalidKeySpecException, IOException {
		Object trileadKey = null;
		if(manager.isKeyLoaded(pubkey.getNickname())) {
			// load this key from memory if its already there
			Log.d(TAG, String.format("Found unlocked key '%s' already in-memory", pubkey.getNickname()));

			if (pubkey.isConfirmUse()) {
				if (promptForPubkeyUse(pubkey.getNickname()))
					return false;
			}

			trileadKey = manager.getKey(pubkey.getNickname());
		} else {
			// otherwise load key from database and prompt for password as needed
			String password = null;
			if (pubkey.isEncrypted()) {
				password = bridge.getPromptHelper().requestStringPrompt(null,
						manager.res.getString(R.string.prompt_pubkey_password, pubkey.getNickname()));

				// Something must have interrupted the prompt.
				if (password == null)
					return false;
			}

			if(PubkeyDatabase.KEY_TYPE_IMPORTED.equals(pubkey.getType())) {
				// load specific key using pem format
//				trileadKey = PEMDecoder.decode(new String(pubkey.getPrivateKey()).toCharArray(), password);
			} else {
				// load using internal generated format
				PrivateKey privKey;
				try {
					privKey = PubkeyUtils.decodePrivate(pubkey.getPrivateKey(),
							pubkey.getType(), password);
				} catch (Exception e) {
					String message = String.format("Bad password for key '%s'. Authentication failed.", pubkey.getNickname());
					Log.e(TAG, message, e);
					bridge.outputLine(message);
					return false;
				}

				PublicKey pubKey = PubkeyUtils.decodePublic(pubkey.getPublicKey(),
						pubkey.getType());

				// convert key to trilead format
//				trileadKey = PubkeyUtils.convertToTrilead(privKey, pubKey);
				Log.d(TAG, "Unlocked key " + PubkeyUtils.formatKey(pubKey));
			}

			Log.d(TAG, String.format("Unlocked key '%s'", pubkey.getNickname()));

			// save this key in-memory if option enabled
			if(manager.isSavingKeys()) {
				manager.addKey(pubkey, trileadKey);
			}
		}

		return tryPublicKey(host.getUsername(), pubkey.getNickname(), trileadKey);
	}

	private boolean tryPublicKey(String username, String keyNickname, Object trileadKey) throws IOException {
		//bridge.outputLine(String.format("Attempting 'publickey' with key '%s' [%s]...", keyNickname, trileadKey.toString()));
//		boolean success = connection.authenticateWithPublicKey(username, trileadKey);
//		if(!success)
//			bridge.outputLine(manager.res.getString(R.string.terminal_auth_pubkey_fail, keyNickname));
//		return success;
		return false;
	}

	/**
	 * Internal method to request actual PTY terminal once we've finished
	 * authentication. If called before authenticated, it will just fail.
	 */
	private void finishConnection() {
		Log.d(TAG, "Authentication complete");
		authenticated = true;

		for (PortForwardBean portForward : portForwards) {
			try {
				enablePortForward(portForward);
				bridge.outputLine(manager.res.getString(R.string.terminal_enable_portfoward, portForward.getDescription()));
			} catch (Exception e) {
				Log.e(TAG, "Error setting up port forward during connect", e);
			}
		}

		if (!host.getWantSession()) {
			bridge.outputLine(manager.res.getString(R.string.terminal_no_session));
			bridge.onConnected();
			return;
		}

		session = connection.openSession();

		session.requestPTY(getEmulation(), columns, rows, width, height);
		// Set some environment variables here, I presume
		session.openShell();

		stdin = session.getStdin();
		stdout = session.getStdout();
		stderr = session.getStderr();

//		try {
//			session = connection.openSession();
//
//			if (!useAuthAgent.equals(HostDatabase.AUTHAGENT_NO))
//				session.requestAuthAgentForwarding(this);
//
//			session.requestPTY(getEmulation(), columns, rows, width, height, null);
//			session.startShell();
//
//			stdin = session.getStdin();
//			stdout = session.getStdout();
//			stderr = session.getStderr();

			sessionOpen = true;

			bridge.onConnected();
//		} catch (IOException e1) {
//			Log.e(TAG, "Problem while trying to create PTY in finishConnection()", e1);
//		}

	}

	@Override
	public void connect() {
		connection = new Session(host.getHostname(), host.getPort());
		try {
			connection.connect();
		} catch (UnknownHostException e) {
			Log.e(TAG, "unknown host", e);

			bridge.outputLine("Unknown host " + host.getHostname());

			onDisconnect();
			return;
		} catch (IOException e) {
			Log.e(TAG, "Problem in SSH connection thread during authentication", e);

			// Display the reason in the text.
			bridge.outputLine(e.getCause().getMessage());

			onDisconnect();
			return;
		}

		connected = true;

		String authMethods = connection.getAuthenticationMethods(host.getUsername());
		Log.d(TAG, "Auth methods: " + authMethods);

		ConnectionInfo connectionInfo = connection.getConnectionInfo();
		if (connectionInfo.c2sCrypto.equals(connectionInfo.s2cCrypto)) {
			bridge.outputLine(manager.res.getString(
					R.string.terminal_using_algorithm,
					connectionInfo.c2sCrypto,
					connectionInfo.c2sMAC));
		} else {
			bridge.outputLine(manager.res.getString(
					R.string.terminal_using_c2s_algorithm,
					connectionInfo.c2sCrypto,
					connectionInfo.c2sMAC));
			bridge.outputLine(manager.res.getString(
					R.string.terminal_using_s2c_algorithm,
					connectionInfo.s2cCrypto,
					connectionInfo.s2cMAC));
		}

		try {
			// enter a loop to keep trying until authentication
			int tries = 0;
			while (connected && !authenticated && tries++ < AUTH_TRIES) {
				authenticate();

				// sleep to make sure we dont kill system
				Thread.sleep(1000);
			}
		} catch(Exception e) {
			Log.e(TAG, "Problem in SSH connection thread during authentication", e);
		}
	}

	@Override
	public void close() {
//		connected = false;
//
//		if (session != null)
//			session.close();
//		if (connection != null)
//			connection.close();
	}

	private void onDisconnect() {
		close();

		bridge.dispatchDisconnect(false);
	}

	@Override
	public void flush() throws IOException {
		if (stdin != null)
			stdin.flush();
	}

	@Override
	public int read(byte[] buffer, int start, int len) throws IOException { return 0; }

	@Override
	public int read(ByteBuffer buffer, int start, int len) throws IOException {
		int bytesRead = 0;

		bytesRead = stdout.read(buffer, start, len);
//		if (session == null)
//			return 0;
//
//		int newConditions = session.waitForCondition(conditions, 0);
//
//		if ((newConditions & ChannelCondition.STDOUT_DATA) != 0) {
//			bytesRead = stdout.read(buffer, start, len);
//		}
//
//		if ((newConditions & ChannelCondition.STDERR_DATA) != 0) {
//			byte discard[] = new byte[256];
//			while (stderr.available() > 0) {
//				stderr.read(discard);
//			}
//		}
//
//		if ((newConditions & ChannelCondition.EOF) != 0) {
//			onDisconnect();
//			throw new IOException("Remote end closed connection");
//		}

		return bytesRead;
	}

	@Override
	public void write(byte[] buffer) throws IOException {
		if (stdin != null)
			stdin.write(buffer);
	}

	@Override
	public void write(int c) throws IOException {
		if (stdin != null)
			stdin.write(c);
	}

	@Override
	public Map<String, String> getOptions() {
		Map<String, String> options = new HashMap<String, String>();

		options.put("compression", Boolean.toString(compression));

		return options;
	}

	@Override
	public void setOptions(Map<String, String> options) {
		if (options.containsKey("compression"))
			compression = Boolean.parseBoolean(options.get("compression"));
	}

	public static String getProtocolName() {
		return PROTOCOL;
	}

	@Override
	public boolean isSessionOpen() {
		return sessionOpen;
	}

	@Override
	public boolean isConnected() {
		return connected;
	}

	public void connectionLost(Throwable reason) {
		onDisconnect();
	}

	@Override
	public boolean canForwardPorts() {
		return true;
	}

	@Override
	public List<PortForwardBean> getPortForwards() {
		return portForwards;
	}

	@Override
	public boolean addPortForward(PortForwardBean portForward) {
		return portForwards.add(portForward);
	}

	@Override
	public boolean removePortForward(PortForwardBean portForward) {
		// Make sure we don't have a phantom forwarder.
		disablePortForward(portForward);

		return portForwards.remove(portForward);
	}

	@Override
	public boolean enablePortForward(PortForwardBean portForward) {
		if (!portForwards.contains(portForward)) {
			Log.e(TAG, "Attempt to enable port forward not in list");
			return false;
		}

		if (!authenticated)
			return false;

//		if (HostDatabase.PORTFORWARD_LOCAL.equals(portForward.getType())) {
//			LocalPortForwarder lpf = null;
//			try {
//				lpf = connection.createLocalPortForwarder(
//						new InetSocketAddress(InetAddress.getLocalHost(), portForward.getSourcePort()),
//						portForward.getDestAddr(), portForward.getDestPort());
//			} catch (IOException e) {
//				Log.e(TAG, "Could not create local port forward", e);
//				return false;
//			}
//
//			if (lpf == null) {
//				Log.e(TAG, "returned LocalPortForwarder object is null");
//				return false;
//			}
//
//			portForward.setIdentifier(lpf);
//			portForward.setEnabled(true);
//			return true;
//		} else if (HostDatabase.PORTFORWARD_REMOTE.equals(portForward.getType())) {
//			try {
//				connection.requestRemotePortForwarding("", portForward.getSourcePort(), portForward.getDestAddr(), portForward.getDestPort());
//			} catch (IOException e) {
//				Log.e(TAG, "Could not create remote port forward", e);
//				return false;
//			}
//
//			portForward.setEnabled(false);
//			return true;
//		} else if (HostDatabase.PORTFORWARD_DYNAMIC5.equals(portForward.getType())) {
//			DynamicPortForwarder dpf = null;
//
//			try {
//				dpf = connection.createDynamicPortForwarder(
//						new InetSocketAddress(InetAddress.getLocalHost(), portForward.getSourcePort()));
//			} catch (IOException e) {
//				Log.e(TAG, "Could not create dynamic port forward", e);
//				return false;
//			}
//
//			portForward.setIdentifier(dpf);
//			portForward.setEnabled(true);
//			return true;
//		} else {
//			// Unsupported type
//			Log.e(TAG, String.format("attempt to forward unknown type %s", portForward.getType()));
//			return false;
//		}
		return false;
	}

	@Override
	public boolean disablePortForward(PortForwardBean portForward) {
		if (!portForwards.contains(portForward)) {
			Log.e(TAG, "Attempt to disable port forward not in list");
			return false;
		}

		if (!authenticated)
			return false;

//		if (HostDatabase.PORTFORWARD_LOCAL.equals(portForward.getType())) {
//			LocalPortForwarder lpf = null;
//			lpf = (LocalPortForwarder)portForward.getIdentifier();
//
//			if (!portForward.isEnabled() || lpf == null) {
//				Log.d(TAG, String.format("Could not disable %s; it appears to be not enabled or have no handler", portForward.getNickname()));
//				return false;
//			}
//
//			portForward.setEnabled(false);
//
//			try {
//				lpf.close();
//			} catch (IOException e) {
//				Log.e(TAG, "Could not stop local port forwarder, setting enabled to false", e);
//				return false;
//			}
//
//			return true;
//		} else if (HostDatabase.PORTFORWARD_REMOTE.equals(portForward.getType())) {
//			portForward.setEnabled(false);
//
//			try {
//				connection.cancelRemotePortForwarding(portForward.getSourcePort());
//			} catch (IOException e) {
//				Log.e(TAG, "Could not stop remote port forwarding, setting enabled to false", e);
//				return false;
//			}
//
//			return true;
//		} else if (HostDatabase.PORTFORWARD_DYNAMIC5.equals(portForward.getType())) {
//			DynamicPortForwarder dpf = null;
//			dpf = (DynamicPortForwarder)portForward.getIdentifier();
//
//			if (!portForward.isEnabled() || dpf == null) {
//				Log.d(TAG, String.format("Could not disable %s; it appears to be not enabled or have no handler", portForward.getNickname()));
//				return false;
//			}
//
//			portForward.setEnabled(false);
//
//			try {
//				dpf.close();
//			} catch (IOException e) {
//				Log.e(TAG, "Could not stop dynamic port forwarder, setting enabled to false", e);
//				return false;
//			}
//
//			return true;
//		} else {
			// Unsupported type
			Log.e(TAG, String.format("attempt to forward unknown type %s", portForward.getType()));
			return false;
//		}
	}

	@Override
	public void setDimensions(int columns, int rows, int width, int height) {
		this.columns = columns;
		this.rows = rows;

//		if (sessionOpen) {
//			try {
//				session.resizePTY(columns, rows, width, height);
//			} catch (IOException e) {
//				Log.e(TAG, "Couldn't send resize PTY packet", e);
//			}
//		}
	}

	@Override
	public int getDefaultPort() {
		return DEFAULT_PORT;
	}

	@Override
	public String getDefaultNickname(String username, String hostname, int port) {
		if (port == DEFAULT_PORT) {
			return String.format("%s@%s", username, hostname);
		} else {
			return String.format("%s@%s:%d", username, hostname, port);
		}
	}

	public static Uri getUri(String input) {
		Matcher matcher = hostmask.matcher(input);

		if (!matcher.matches())
			return null;

		StringBuilder sb = new StringBuilder();

		sb.append(PROTOCOL)
			.append("://")
			.append(Uri.encode(matcher.group(1)))
			.append('@')
			.append(matcher.group(2));

		String portString = matcher.group(4);
		int port = DEFAULT_PORT;
		if (portString != null) {
			try {
				port = Integer.parseInt(portString);
				if (port < 1 || port > 65535) {
					port = DEFAULT_PORT;
				}
			} catch (NumberFormatException nfe) {
				// Keep the default port
			}
		}

		if (port != DEFAULT_PORT) {
			sb.append(':')
				.append(port);
		}

		sb.append("/#")
			.append(Uri.encode(input));

		Uri uri = Uri.parse(sb.toString());

		return uri;
	}

	/**
	 * Handle challenges from keyboard-interactive authentication mode.
	 */
	public String[] replyToChallenge(String name, String instruction, int numPrompts, String[] prompt, boolean[] echo) {
		String[] responses = new String[numPrompts];
		for(int i = 0; i < numPrompts; i++) {
			// request response from user for each prompt
			responses[i] = bridge.promptHelper.requestStringPrompt(instruction, prompt[i]);
		}
		return responses;
	}

	@Override
	public HostBean createHost(Uri uri) {
		HostBean host = new HostBean();

		host.setProtocol(PROTOCOL);
		host.setNickname(uri.getFragment());
		host.setHostname(uri.getHost());

		int port = uri.getPort();
		if (port < 0)
			port = DEFAULT_PORT;
		host.setPort(port);
		host.setUsername(uri.getUserInfo());

		return host;
	}

	@Override
	public void getSelectionArgs(Uri uri, Map<String, String> selection) {
		selection.put(HostDatabase.FIELD_HOST_PROTOCOL, PROTOCOL);
		selection.put(HostDatabase.FIELD_HOST_NICKNAME, uri.getFragment());
		selection.put(HostDatabase.FIELD_HOST_HOSTNAME, uri.getHost());

		int port = uri.getPort();
		if (port < 0)
			port = DEFAULT_PORT;
		selection.put(HostDatabase.FIELD_HOST_PORT, Integer.toString(port));
		selection.put(HostDatabase.FIELD_HOST_USERNAME, uri.getUserInfo());
	}

	@Override
	public void setCompression(boolean compression) {
		this.compression = compression;
	}

	public static String getFormatHint(Context context) {
		return String.format("%s@%s:%s",
				context.getString(R.string.format_username),
				context.getString(R.string.format_hostname),
				context.getString(R.string.format_port));
	}

	@Override
	public void setUseAuthAgent(String useAuthAgent) {
		this.useAuthAgent = useAuthAgent;
	}

	public Map<String,byte[]> retrieveIdentities() {
		Map<String,byte[]> pubKeys = new HashMap<String,byte[]>(manager.loadedKeypairs.size());

		for (Entry<String,KeyHolder> entry : manager.loadedKeypairs.entrySet()) {
			Object trileadKey = entry.getValue().trileadKey;

//			try {
//				if (trileadKey instanceof RSAPrivateKey) {
//					RSAPublicKey pubkey = ((RSAPrivateKey) trileadKey).getPublicKey();
//					pubKeys.put(entry.getKey(), RSASHA1Verify.encodeSSHRSAPublicKey(pubkey));
//				} else if (trileadKey instanceof DSAPrivateKey) {
//					DSAPublicKey pubkey = ((DSAPrivateKey) trileadKey).getPublicKey();
//					pubKeys.put(entry.getKey(), DSASHA1Verify.encodeSSHDSAPublicKey(pubkey));
//				} else
//					continue;
//			} catch (IOException e) {
//				continue;
//			}
		}

		return pubKeys;
	}

	public Object getPrivateKey(byte[] publicKey) {
		String nickname = manager.getKeyNickname(publicKey);

		if (nickname == null)
			return null;

		if (useAuthAgent.equals(HostDatabase.AUTHAGENT_NO)) {
			Log.e(TAG, "");
			return null;
		} else if (useAuthAgent.equals(HostDatabase.AUTHAGENT_CONFIRM) ||
				manager.loadedKeypairs.get(nickname).bean.isConfirmUse()) {
			if (!promptForPubkeyUse(nickname))
				return null;
		}
		return manager.getKey(nickname);
	}

	private boolean promptForPubkeyUse(String nickname) {
		Boolean result = bridge.promptHelper.requestBooleanPrompt(null,
				manager.res.getString(R.string.prompt_allow_agent_to_use_key,
						nickname));
		return result;
	}

	public boolean addIdentity(Object key, String comment, boolean confirmUse, int lifetime) {
		PubkeyBean pubkey = new PubkeyBean();
//		pubkey.setType(PubkeyDatabase.KEY_TYPE_IMPORTED);
		pubkey.setNickname(comment);
		pubkey.setConfirmUse(confirmUse);
		pubkey.setLifetime(lifetime);
		manager.addKey(pubkey, key);
		return true;
	}

	public boolean removeAllIdentities() {
		manager.loadedKeypairs.clear();
		return true;
	}

	public boolean removeIdentity(byte[] publicKey) {
		return manager.removeKey(publicKey);
	}

	public boolean isAgentLocked() {
		return agentLockPassphrase != null;
	}

	public boolean requestAgentUnlock(String unlockPassphrase) {
		if (agentLockPassphrase == null)
			return false;

		if (agentLockPassphrase.equals(unlockPassphrase))
			agentLockPassphrase = null;

		return agentLockPassphrase == null;
	}

	public boolean setAgentLock(String lockPassphrase) {
		if (agentLockPassphrase != null)
			return false;

		agentLockPassphrase = lockPassphrase;
		return true;
	}
}
