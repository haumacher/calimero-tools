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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.StringTokenizer;

import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.exception.KNXIllegalArgumentException;
import tuwien.auto.calimero.log.LogLevel;
import tuwien.auto.calimero.log.LogManager;
import tuwien.auto.calimero.log.LogStreamWriter;
import tuwien.auto.calimero.log.LogWriter;
import tuwien.auto.calimero.mgmt.PropertyAdapter;
import tuwien.auto.calimero.mgmt.PropertyClient;

/**
 * A tool for Calimero showing features of the {@link PropertyClient} used for KNX
 * property access.
 * <p>
 * PropClient is a console based tool implementation for reading and writing KNX
 * properties. It supports network access using a KNXnet/IP connection or FT1.2
 * connection. To start the PropClient, invoke the <code>main</code>-method of this class.
 * Take a look at the command line options to configure the tool with the desired
 * communication settings.
 * <p>
 * The main part of this tool implementation interacts with the PropertyClient interface,
 * which offers high level access to KNX property information. It also shows creation of
 * the {@link PropertyAdapter}, necessary for a property client to work. All queried
 * property values, as well as occurring problems are written to <code>System.out
 * </code>.
 *
 * @author B. Malinowsky
 */
public class PropClient implements Runnable
{
	private static final String tool = "PropClient";
	private static final String version = "1.1";

	class PropertyEx extends tuwien.auto.calimero.tools.Property
	{
		public PropertyEx(final String[] args)
		{
			super(args);
		}

		protected void runCommand(final String[] cmd)
		{
			// ignore any command supplied on command line
			options.remove("command");
			System.out.println("exit - close connection and exit");
			System.out.println();
			// show some command info
			super.runCommand(new String[] { "?" });
			runReaderLoop(PropClient.this);
		}

		private void runReaderLoop(final PropClient propClient)
		{
			// create reader for user input
			final BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
			String[] args;
			try {
				while ((args = propClient.readLine(r)) != null) {
					if ("exit".equalsIgnoreCase(args[0]))
						break;
					if (args.length > 0)
						super.runCommand(args);
				}
			}
			catch (final InterruptedException e) {
				System.out.println("received quit (interrupt), closing ...");
			}
			catch (final InterruptedIOException e) {
				System.out.println("received quit (interrupt), closing ...");
			}
			catch (final IOException e) {
				System.out.println("I/O error, " + e.getMessage());
			}
		}

		void showVersion()
		{
			Property.out.log(LogLevel.ALWAYS,
					tool + " version " + version + " using " + Settings.getLibraryHeader(false),
					null);
		}
	}

	private final PropertyEx property;

	/**
	 * Constructs a new PropClient.
	 * <p>
	 *
	 * @param args options for the property client tool, see {@link #main(String[])}
	 * @throws KNXIllegalArgumentException on missing or wrong formatted option value
	 */
	public PropClient(final String[] args)
	{
		property = new PropertyEx(args);
	}

	/**
	 * Entry point for running the PropClient.
	 * <p>
	 * An IP host or port identifier has to be supplied to specify the endpoint for the
	 * KNX network access.<br>
	 * To show the usage message of this tool on the console, supply the command line
	 * option -help (or -h).<br>
	 * Command line options are treated case sensitive. Available options for the property
	 * client:
	 * <ul>
	 * <li><code>-help -h</code> show help message</li>
	 * <li><code>-version</code> show tool/library version and exit</li>
	 * <li><code>-verbose -v</code> enable verbose status output</li>
	 * <li><code>-local -l</code> local device management</li>
	 * <li><code>-remote -r</code> <i>KNX addr</i> &nbsp;remote property service</li>
	 * <li><code>-definitions -d</code> <i>file</i> &nbsp;use property definition file</li>
	 * <li><code>-localhost</code> <i>id</i> &nbsp;local IP/host name</li>
	 * <li><code>-localport</code> <i>number</i> &nbsp;local UDP port (default system
	 * assigned)</li>
	 * <li><code>-port -p</code> <i>number</i> &nbsp;UDP port on host (default 3671)</li>
	 * <li><code>-nat -n</code> enable Network Address Translation</li>
	 * <li><code>-serial -s</code> use FT1.2 serial communication</li>
	 * </ul>
	 * For local device management these options are available:
	 * <ul>
	 * <li><code>-emulatewriteenable -e</code> check write-enable of a property</li>
	 * </ul>
	 * For remote property service these options are available:
	 * <ul>
	 * <li><code>-routing</code> use KNXnet/IP routing</li>
	 * <li><code>-medium -m</code> <i>id</i> &nbsp;KNX medium [tp0|tp1|p110|p132|rf]
	 * (defaults to tp1)</li>
	 * <li><code>-connect -c</code> connection oriented mode</li>
	 * <li><code>-authorize -a</code> <i>key</i> &nbsp;authorize key to access KNX device</li>
	 * </ul>
	 *
	 * @param args command line options for property client
	 */
	public static void main(final String[] args)
	{
		final LogWriter w = LogStreamWriter.newUnformatted(LogLevel.INFO, System.out, true, false);
		LogManager.getManager().addWriter(null, w);
		try {
			final PropClient pc = new PropClient(args);
			pc.run();
		}
		catch (final Throwable t) {
			Property.out.error("client error", t);
		}
		LogManager.getManager().shutdown(true);
	}

	/* (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run()
	{
		property.run();
	}

	/**
	 * Writes command prompt and waits for a command request from the user.
	 * <p>
	 *
	 * @param r input reader
	 * @return array with command and command arguments
	 * @throws IOException on I/O error
	 * @throws InterruptedException
	 */
	private String[] readLine(final BufferedReader r) throws IOException, InterruptedException
	{
		System.out.print("> ");
		synchronized (this) {
			while (!r.ready())
				wait(200);
		}
		final String line = r.readLine();
		return line != null ? split(line) : null;
	}

	private static String[] split(final String text)
	{
		final StringTokenizer st = new StringTokenizer(text, " \t");
		final String[] tokens = new String[st.countTokens()];
		for (int i = 0; i < tokens.length; ++i)
			tokens[i] = st.nextToken();
		return tokens;
	}
}
