/**
 * This file is a part of Angry IP Scanner source code,
 * see http://www.angryip.org/ for more information.
 * Licensed under GPLv2.
 */
package net.azib.ipscan.gui.actions;

import net.azib.ipscan.Main;
import net.azib.ipscan.config.Labels;
import net.azib.ipscan.config.Version;
import net.azib.ipscan.core.ScanningResult;
import net.azib.ipscan.core.UserErrorException;
import net.azib.ipscan.core.state.ScanningState;
import net.azib.ipscan.core.state.StateMachine;
import net.azib.ipscan.exporters.ExportProcessor;
import net.azib.ipscan.exporters.ExportProcessor.ScanningResultFilter;
import net.azib.ipscan.exporters.Exporter;
import net.azib.ipscan.exporters.ExporterRegistry;
import net.azib.ipscan.fetchers.IPFetcher;
import net.azib.ipscan.fetchers.PingFetcher;
import net.azib.ipscan.fetchers.PortsFetcher;
import net.azib.ipscan.gui.ResultTable;
import net.azib.ipscan.gui.StatusBar;
import net.azib.ipscan.gui.feeders.FeederGUIRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MessageBox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static net.azib.ipscan.core.ScanningResult.ResultType.*;
import static net.azib.ipscan.util.IOUtils.closeQuietly;

/**
 * FileActions
 * 
 * @author Anton Keks
 */
public class ScanMenuActions {

	public static boolean isLoadedFromFile = false;

	public static final class Quit implements Listener {
		public void handleEvent(Event event) {
			event.display.getActiveShell().close();
		}
	}

	public static class LoadFromFile implements Listener {
		private final ExporterRegistry exporterRegistry;
		private FeederGUIRegistry feederRegistry;
		private final ResultTable resultTable;
		private final StateMachine stateMachine;

		public LoadFromFile(ExporterRegistry exporterRegistry, FeederGUIRegistry feederRegistry, ResultTable resultTable, StateMachine stateMachine) {
			this.exporterRegistry = exporterRegistry;
			this.feederRegistry = feederRegistry;
			this.resultTable = resultTable;
			this.stateMachine = stateMachine;
		}

		public void handleEvent(Event event) {
			FileDialog fileDialog = new FileDialog(resultTable.getShell(), SWT.OPEN);

			// gather lists of extensions and exporter names
			List<String> extensions2 = new ArrayList<String>();
			List<String> descriptions = new ArrayList<String>();
			StringBuffer labelBuffer = new StringBuffer(Labels.getLabel("title.load"));
			addFileExtensions(extensions2, descriptions, labelBuffer);

			List<String> extensions = new ArrayList<String>();
			extensions.add(extensions2.get(0));

			fileDialog.setText(labelBuffer.toString());
			fileDialog.setFilterExtensions(extensions.toArray(new String[extensions.size()]));
			fileDialog.setFilterNames(descriptions.toArray(new String[descriptions.size()]));

			String fileName = fileDialog.open();
			if (fileName == null) return;

			loadResultsFrom(fileName);
		}

		private void loadResultsFrom(String fileName) {
			BufferedReader reader = null;
			try {
				reader = new BufferedReader(new FileReader(fileName));
				isLoadedFromFile = true;

				resultTable.removeAll();
				String originalStartIP = null;
				String startIPAfterLoad = null;
				String endIp = null;

				int ipIndex = 0, pingIndex = 1, portsIndex = 3;
				String ipLabel = Labels.getLabel(IPFetcher.ID);
				int i = 0;
				String line;
				while ((line = reader.readLine()) != null) {
					i++;
					if (i == 1) continue;
					String[] sp = line.split("\\s+");

					if (i == 4) {
						originalStartIP = sp[1];
						startIPAfterLoad = sp[1];
						endIp = sp[3];
					}

					if (ipLabel.equals(sp[ipIndex])) {
						pingIndex = asList(sp).indexOf(Labels.getLabel(PingFetcher.ID));
						portsIndex = asList(sp).indexOf(Labels.getLabel(PortsFetcher.ID));
					}

					if (sp.length < 3 || i < 8) continue;

					InetAddress addr = InetAddress.getByName(sp[ipIndex]);
					startIPAfterLoad = sp[ipIndex];

					ScanningResult r = new ScanningResult(addr, sp.length);
					if (portsIndex > 0 && sp[portsIndex].matches("\\d.*")) r.setType(WITH_PORTS);
					else if (pingIndex > 0 && sp[pingIndex].matches("\\d.*")) r.setType(ALIVE);
					else r.setType(DEAD);

					r.setValues(sp);
					resultTable.addOrUpdateResultRow(r);
				}

				feederRegistry.select("feeder.range");
				feederRegistry.current().unserialize(startIPAfterLoad, endIp);
				stateMachine.transitionToNext();
			}
			catch (Exception e) {
				throw new UserErrorException("fileLoad.failed", e);
			}
			finally {
				closeQuietly(reader);
			}
		}

		private void addFileExtensions(List<String> extensions, List<String> descriptions, StringBuffer sb) {
			sb.append(" (");
			for (Exporter exporter : exporterRegistry) {
				extensions.add("*." + exporter.getFilenameExtension());
				sb.append(exporter.getFilenameExtension()).append(", ");
				descriptions.add(Labels.getLabel(exporter.getId()));
			}
			// strip the last comma
			sb.delete(sb.length() - 2, sb.length());
			sb.append(")");
		}
	}

	static abstract class SaveResults implements Listener {
		private final ExporterRegistry exporterRegistry;
		private final ResultTable resultTable;
		private final StatusBar statusBar;
		private final boolean isSelection;
		private final StateMachine stateMachine;

		SaveResults(ExporterRegistry exporterRegistry, ResultTable resultTable, StatusBar statusBar, StateMachine stateMachine, boolean isSelection) {
			this.exporterRegistry = exporterRegistry;
			this.resultTable = resultTable;
			this.statusBar = statusBar;
			this.stateMachine = stateMachine;
			this.isSelection = isSelection;
		}

		public void handleEvent(Event event) {
			if (resultTable.getItemCount() <= 0) {
				throw new UserErrorException("commands.noResults");
			}

			if (!stateMachine.inState(ScanningState.IDLE)) {
				// ask the user whether to save incomplete results
				MessageBox box = new MessageBox(resultTable.getShell(), SWT.YES | SWT.NO | SWT.ICON_WARNING | SWT.SHEET);
				box.setText(Version.NAME);
				box.setMessage(Labels.getLabel("exception.ExporterException.scanningInProgress"));
				if (box.open() != SWT.YES)
					return;
			}

			FileDialog fileDialog = new FileDialog(resultTable.getShell(), SWT.SAVE);

			// gather lists of extensions and exporter names
			List<String> extensions = new ArrayList<String>();
			List<String> descriptions = new ArrayList<String>();
			StringBuffer labelBuffer = new StringBuffer(Labels.getLabel(isSelection ? "title.exportSelection" : "title.exportAll"));
			addFileExtensions(extensions, descriptions, labelBuffer);

			fileDialog.setText(labelBuffer.toString());
			fileDialog.setFilterExtensions(extensions.toArray(new String[extensions.size()]));
			fileDialog.setFilterNames(descriptions.toArray(new String[descriptions.size()]));

			String fileName = fileDialog.open();
			if (fileName == null) return;

			Exporter exporter = exporterRegistry.createExporter(fileName);

			statusBar.setStatusText(Labels.getLabel("state.exporting"));

			// TODO: expose appending feature in the GUI
			ExportProcessor exportProcessor = new ExportProcessor(exporter, new File(fileName), false);

			// in case of isSelection we need to create our filter
			ScanningResultFilter filter = null;
			if (isSelection) {
				filter = new ScanningResultFilter() {
					public boolean apply(int index, ScanningResult result) {
						return resultTable.isSelected(index);
					}
				};
			}

			exportProcessor.process(resultTable.getScanningResults(), filter);
			statusBar.setStatusText(null);
		}

		private void addFileExtensions(List<String> extensions, List<String> descriptions, StringBuffer sb) {
			sb.append(" (");
			for (Exporter exporter : exporterRegistry) {
				extensions.add("*." + exporter.getFilenameExtension());
				sb.append(exporter.getFilenameExtension()).append(", ");
				descriptions.add(Labels.getLabel(exporter.getId()));
			}
			// strip the last comma
			sb.delete(sb.length() - 2, sb.length());
			sb.append(")");
		}
	}

	public static final class SaveAll extends SaveResults {
		public SaveAll(ExporterRegistry exporterRegistry, ResultTable resultTable, StatusBar statusBar, StateMachine stateMachine) {
			super(exporterRegistry, resultTable, statusBar, stateMachine, false);
		}
	}

	public static final class SaveSelection extends SaveResults {
		public SaveSelection(ExporterRegistry exporterRegistry, ResultTable resultTable, StatusBar statusBar, StateMachine stateMachine) {
			super(exporterRegistry, resultTable, statusBar, stateMachine, true);
		}
	}

	public static final class NewWindow implements Listener {
		public void handleEvent(Event event) {
			// start another instance in a new thread
			// doesn't currently work...
			new Thread("main") {
				public void run() {					
					Main.main();
				}
			}.start();
		}
	}

}