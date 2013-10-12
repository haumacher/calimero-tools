/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2006, 2011 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package tuwien.auto.calimero.tools;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.cemi.CEMI;
import tuwien.auto.calimero.exception.KNXException;
import tuwien.auto.calimero.exception.KNXIllegalArgumentException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.KNXNetworkLinkFT12;
import tuwien.auto.calimero.link.KNXNetworkLinkIP;
import tuwien.auto.calimero.link.NetworkLinkListener;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.link.medium.PLSettings;
import tuwien.auto.calimero.link.medium.RFSettings;
import tuwien.auto.calimero.link.medium.TPSettings;
import tuwien.auto.calimero.log.LogLevel;
import tuwien.auto.calimero.log.LogManager;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogStreamWriter;
import tuwien.auto.calimero.log.LogWriter;

/**
 * A tool for Calimero allowing monitoring of KNX group communication.
 *
 * @author B. Malinowsky, B. Haumacher
 */
public class GroupMonitor implements Runnable
{
	private static final String tool = "GroupMonitor";
	private static final String version = "1.1";
	private static final String sep = System.getProperty("line.separator");

	static LogService out = LogManager.getManager().getLogService("tools");

	private final Map<String, Object> options = new HashMap<String, Object>();
	private KNXNetworkLink link;

	private final NetworkLinkListener l = new NetworkLinkListener() {
		
		@Override
		public void linkClosed(CloseEvent e) {
			out.info("network monitor closed (" + e.getReason() + ")");
			synchronized (GroupMonitor.this) {
				GroupMonitor.this.notify();
			}
		}
		
		@Override
		public void indication(FrameEvent e) {
			GroupMonitor.this.onIndication(e);
		}
		
		@Override
		public void confirmation(FrameEvent e) {
			GroupMonitor.this.onConfirmation(e);
		}
	};

	/**
	 * Creates a new {@link GroupMonitor} instance using the supplied options.
	 * <p>
	 * See {@link #main(String[])} for a list of options.
	 *
	 * @param args list with options
	 * @throws KNXIllegalArgumentException on unknown/invalid options
	 */
	public GroupMonitor(final String[] args)
	{
		try {
			// read the command line options
			parseOptions(args);
		}
		catch (final KNXIllegalArgumentException e) {
			throw e;
		}
		catch (final RuntimeException e) {
			throw new KNXIllegalArgumentException(e.getMessage(), e);
		}
	}

	/**
	 * Entry point for running the {@link GroupMonitor}.
	 * <p>
	 * An IP host or port identifier has to be supplied, specifying the endpoint for the KNX network
	 * access.<br>
	 * To show the usage message of this tool on the console, supply the command line option -help
	 * (or -h).<br>
	 * Command line options are treated case sensitive. Available options for network monitoring:
	 * <ul>
	 * <li><code>-help -h</code> show help message</li>
	 * <li><code>-version</code> show tool/library version and exit</li>
	 * <li><code>-verbose -v</code> enable verbose status output</li>
	 * <li><code>-localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>-localport</code> <i>number</i> &nbsp;local UDP port (default system assigned)</li>
	 * <li><code>-port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>-nat -n</code> enable Network Address Translation</li>
	 * <li><code>-serial -s</code> use FT1.2 serial communication</li>
	 * <li><code>-medium -m</code> <i>id</i> &nbsp;KNX medium [tp0|tp1|p110|p132|rf] (defaults to
	 * tp1)</li>
	 * </ul>
	 *
	 * @param args command line options for network monitoring
	 */
	public static void main(final String[] args)
	{
		final LogWriter w = new LogStreamWriter(LogLevel.WARN, System.out, true, false);
		LogManager.getManager().addWriter(null, w);
		try {
			final GroupMonitor m = new GroupMonitor(args);
			if (m.options.containsKey("verbose"))
				w.setLogLevel(LogLevel.TRACE);

			final ShutdownHandler sh = m.new ShutdownHandler().register();
			m.run();
			sh.unregister();
		}
		catch (final KNXIllegalArgumentException e) {
			out.error("parsing options", e);
		}
		LogManager.getManager().shutdown(true);
	}

	@Override
	public void run()
	{
		Exception thrown = null;
		boolean canceled = false;
		try {
			start();

			// just wait for the network monitor to quit
			synchronized (this) {
				while (link != null && link.isOpen())
					wait(500);
			}
		}
		catch (final InterruptedException e) {
			canceled = true;
			Thread.currentThread().interrupt();
		}
		catch (final KNXException e) {
			thrown = e;
		}
		catch (final RuntimeException e) {
			thrown = e;
		}
		finally {
			quit();
			onCompletion(thrown, canceled);
		}
	}

	/**
	 * Starts the network monitor.
	 * <p>
	 * This method returns after the network monitor was started.
	 *
	 * @throws KNXException on problems creating or connecting the monitor
	 * @throws InterruptedException on interrupted thread
	 */
	public void start() throws KNXException, InterruptedException
	{
		if (options.isEmpty()) {
			out.log(LogLevel.ALWAYS, "A tool for monitoring KNX group communication", null);
			showVersion();
			out.log(LogLevel.ALWAYS, "type -help for help message", null);
			return;
		}
		if (options.containsKey("help")) {
			showUsage();
			return;
		}
		if (options.containsKey("version")) {
			showVersion();
			return;
		}

		link = createLink();

		// listen to monitor link events
		link.addLinkListener(l);
	}

	/**
	 * Quits a running network monitor, otherwise returns immediately.
	 * <p>
	 */
	public void quit()
	{
		if (link != null && link.isOpen()) {
			link.close();
			synchronized (this) {
				notifyAll();
			}
		}
	}

	/**
	 * Called by this tool on receiving a monitor indication frame.
	 * <p>
	 *
	 * @param e the frame event
	 */
	protected void onIndication(final FrameEvent e)
	{
		logFrame(e);
	}
	
	/**
	 * Called by this tool on receiving a monitor confirmation frame.
	 * <p>
	 *
	 * @param e the frame event
	 */
	protected void onConfirmation(final FrameEvent e)
	{
		logFrame(e);
	}

	private void logFrame(final FrameEvent e) {
		CEMI frame = e.getFrame();
		final StringBuffer sb = new StringBuffer();
		sb.append(frame.toString());
		out.log(LogLevel.ALWAYS, sb.toString(), null);
	}

	
	/**
	 * Called by this tool on completion.
	 * <p>
	 *
	 * @param thrown the thrown exception if operation completed due to an raised exception,
	 *        <code>null</code> otherwise
	 * @param canceled whether the operation got canceled before its planned end
	 */
	protected void onCompletion(final Exception thrown, final boolean canceled)
	{
		if (canceled)
			out.info(tool + " stopped");
		if (thrown != null)
			out.error(thrown.getMessage() != null ? thrown.getMessage() : thrown.getClass()
					.getName());
	}

	/**
	 * Creates the KNX network link to access the network specified in <code>options</code>.
	 * <p>
	 *
	 * @return the KNX network monitor link
	 * @throws KNXException on problems on link creation
	 * @throws InterruptedException on interrupted thread
	 */
	private KNXNetworkLink createLink() throws KNXException, InterruptedException
	{
		final KNXMediumSettings medium = (KNXMediumSettings) options.get("medium");
		if (options.containsKey("serial")) {
			// create FT1.2 monitor link
			final String p = (String) options.get("serial");
			try {
				return new KNXNetworkLinkFT12(Integer.parseInt(p), medium);
			}
			catch (final NumberFormatException e) {
				return new KNXNetworkLinkFT12(p, medium);
			}
		}
		// create local and remote socket address for monitor link
		InetAddress localHost = (InetAddress) options.get("localhost");
		Integer localPort = (Integer) options.get("localport");
		final InetSocketAddress local = createLocalSocket(localHost, localPort);
		final InetSocketAddress host = new InetSocketAddress((InetAddress) options.get("host"),
				((Integer) options.get("port")).intValue());
		// create the monitor link, based on the KNXnet/IP protocol
		// specify whether network address translation shall be used,
		// and tell the physical medium of the KNX network
		return new KNXNetworkLinkIP(KNXNetworkLinkIP.TUNNELING, local, host, options.containsKey("nat"), medium);
	}

	/**
	 * Reads all options in the specified array, and puts relevant options into the supplied options
	 * map.
	 * <p>
	 * On options not relevant for doing network monitoring (like <code>help</code>), this method
	 * will take appropriate action (like showing usage information). On occurrence of such an
	 * option, other options will be ignored. On unknown options, a KNXIllegalArgumentException is
	 * thrown.
	 *
	 * @param args array with command line options
	 */
	private void parseOptions(final String[] args)
	{
		if (args.length == 0)
			return;

		// add defaults
		options.put("port", new Integer(KNXnetIPConnection.DEFAULT_PORT));
		options.put("medium", TPSettings.TP1);

		int i = 0;
		for (; i < args.length; i++) {
			final String arg = args[i];
			if (isOption(arg, "-help", "-h")) {
				options.put("help", null);
				return;
			}
			if (isOption(arg, "-version", null)) {
				options.put("version", null);
				return;
			}
			if (isOption(arg, "-verbose", "-v"))
				options.put("verbose", null);
			else if (isOption(arg, "-localhost", null))
				parseHost(args[++i], true, options);
			else if (isOption(arg, "-localport", null))
				options.put("localport", Integer.decode(args[++i]));
			else if (isOption(arg, "-port", "-p"))
				options.put("port", Integer.decode(args[++i]));
			else if (isOption(arg, "-nat", "-n"))
				options.put("nat", null);
			else if (isOption(arg, "-serial", "-s"))
				options.put("serial", null);
			else if (isOption(arg, "-medium", "-m"))
				options.put("medium", getMedium(args[++i]));
			else if (options.containsKey("serial"))
				// add port number/identifier to serial option
				options.put("serial", arg);
			else if (!options.containsKey("host"))
				parseHost(arg, false, options);
			else
				throw new KNXIllegalArgumentException("unknown option " + arg);
		}
		if (!options.containsKey("host") && !options.containsKey("serial"))
			throw new KNXIllegalArgumentException("no host or serial port specified");
	}

	private static boolean isOption(final String arg, final String longOpt, final String shortOpt)
	{
		return arg.equals(longOpt) || shortOpt != null && arg.equals(shortOpt);
	}

	private static void showUsage()
	{
		final StringBuffer sb = new StringBuffer();
		sb.append("usage: ").append(tool).append(" [options] <host|port>").append(sep);
		sb.append("options:").append(sep);
		sb.append("  -help -h                show this help message").append(sep);
		sb.append("  -version                show tool/library version and exit").append(sep);
		sb.append("  -verbose -v             enable verbose status output").append(sep);
		sb.append("  -localhost <id>         local IP/host name").append(sep);
		sb.append("  -localport <number>     local UDP port (default system " + "assigned)")
				.append(sep);
		sb.append("  -port -p <number>       UDP port on host (default ")
				.append(KNXnetIPConnection.DEFAULT_PORT).append(")").append(sep);
		sb.append("  -nat -n                 enable Network Address Translation").append(sep);
		sb.append("  -serial -s              use FT1.2 serial communication").append(sep);
		sb.append("  -medium -m <id>         KNX medium [tp0|tp1|p110|p132|rf] " + "(default tp1)")
				.append(sep);
		out.log(LogLevel.ALWAYS, sb.toString(), null);
	}

	private static void showVersion()
	{
		out.log(LogLevel.ALWAYS,
				tool + " version " + version + " using " + Settings.getLibraryHeader(false), null);
	}

	/**
	 * Creates a medium settings type for the supplied medium identifier.
	 * <p>
	 *
	 * @param id a medium identifier from command line option
	 * @return medium settings object
	 * @throws KNXIllegalArgumentException on unknown medium identifier
	 */
	private static KNXMediumSettings getMedium(final String id)
	{
		if (id.equals("tp0"))
			return TPSettings.TP0;
		else if (id.equals("tp1"))
			return TPSettings.TP1;
		else if (id.equals("p110"))
			return new PLSettings(false);
		else if (id.equals("p132"))
			return new PLSettings(true);
		else if (id.equals("rf"))
			return new RFSettings(null);
		else
			throw new KNXIllegalArgumentException("unknown medium");
	}

	private static void parseHost(final String host, final boolean local, final Map<String, Object> options)
	{
		try {
			options.put(local ? "localhost" : "host", InetAddress.getByName(host));
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed to read host " + host, e);
		}
	}

	private static InetSocketAddress createLocalSocket(final InetAddress host, final Integer port)
	{
		final int p = port != null ? port.intValue() : 0;
		try {
			return host != null ? new InetSocketAddress(host, p) : p != 0 ? new InetSocketAddress(
					InetAddress.getLocalHost(), p) : null;
		}
		catch (final UnknownHostException e) {
			throw new KNXIllegalArgumentException("failed to get local host " + e.getMessage(), e);
		}
	}

	final class ShutdownHandler extends Thread
	{
		public ShutdownHandler register()
		{
			Runtime.getRuntime().addShutdownHook(this);
			return this;
		}

		public void unregister()
		{
			Runtime.getRuntime().removeShutdownHook(this);
		}

		@Override
		public void run()
		{
			quit();
		}
	}
}
