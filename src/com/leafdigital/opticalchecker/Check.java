/*
This file is part of leafdigital Optical media checker.

Optical disc checker is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Optical media checker is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Optical media checker.  If not, see <http://www.gnu.org/licenses/>.

Copyright 2011 Samuel Marshall.
*/
package com.leafdigital.opticalchecker;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;

import javax.swing.*;

/**
 * Checks DVDs (or other removable discs) to ensure that all the data files
 * can be read without disc errors.
 */
public class Check extends JFrame
{
	private JLabel info;
	private Set<File> knownNotOptical = new HashSet<File>();	
	private Set<File> nowProcessing = new HashSet<File>();
	
	private JPanel nextAddPanel;
	
	/**
	 * Constructs frame and starts checker.
	 */
	public Check()
	{
		super("Optical media checker");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		info = new JLabel();
		setInfo();
		info.setPreferredSize(new Dimension(600, info.getPreferredSize().height));
		JPanel inner = new JPanel(new BorderLayout(0, 8));
		inner.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		inner.add(info, BorderLayout.NORTH);
		
		nextAddPanel = new JPanel(new BorderLayout(0, 8));
		inner.add(nextAddPanel, BorderLayout.CENTER);
		
		getContentPane().add(inner, BorderLayout.CENTER);
		
		JLabel copyright = new JLabel("leafdigital Optical media checker / copyright \u00a9 2011 Samuel Marshall");
		copyright.putClientProperty("JComponent.sizeVariant", "mini");
		inner.add(copyright, BorderLayout.SOUTH);
		
		pack();
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		setLocation((screen.width - getWidth())/2, screen.height / 10);
		
		setVisible(true);
		setResizable(false);
		
		new FindThread();
	}
	
	private void setInfo()
	{
		boolean empty;
		synchronized(nowProcessing)
		{
			empty = nowProcessing.isEmpty();
		}
		info.setText(empty 
			? "Insert optical media to begin scan."
			: "Scanning. Insert media into another drive for simultaneous scan.");
	}
	
	private static class VolumeInfo
	{
		private File volume;
		private String type;
		
		private VolumeInfo(File volume, String type)
		{
			this.volume = volume;
			this.type = type;
		}

		/** 
		 * @return Volume file object
		 */
		public File getVolume()
		{
			return volume;
		}

		/** 
		 * @return Type of volume
		 */
		public String getType()
		{
			return type;
		}
	}
	
	private class FindThread extends Thread
	{
		private FindThread()
		{
			start();
		}
		
		@Override
		public void run()
		{
			try
			{
				while(true)
				{
					VolumeInfo info = findOpticalVolume();
					if(info != null)
					{
						new ProcessDiscThread(info);
					}
					Thread.sleep(500);
				}
			}
			catch(Throwable t)
			{
				error(t);
			}
		}
		
		private VolumeInfo findOpticalVolume() throws IOException
		{
			File f = new File("/Volumes");
			File[] contents = f.listFiles();
			if(contents == null)
			{
				contents = new File[0];
			}
			
			for(File file : contents)
			{
				// Is this known to not be an optical disc?
				if(knownNotOptical.contains(file))
				{
					continue;
				}
				
				// Are we currently processing it?
				synchronized(nowProcessing)
				{
					if(nowProcessing.contains(file))
					{
						continue;
					}
				}
				
				// Run diskutil to get info on volume
				Process process = Runtime.getRuntime().exec(
					new String[] {"diskutil", "info", "-plist", file.getPath()});
				BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), "UTF-8"));
				String lastLine = "", opticalMediaType = null, mountPoint = null;				
				while(true)
				{
					String line = reader.readLine();
					if(line == null)
					{
						break;
					}
					if(lastLine.matches(".*<key>OpticalMediaType</key>.*"))
					{
						opticalMediaType = line.replaceAll("^.*<string>(.*?)</string>.*$", "$1");
					}
					if(lastLine.matches(".*<key>MountPoint</key>.*"))
					{
						mountPoint = line.replaceAll("^.*<string>(.*?)</string>.*$", "$1");
					}
					lastLine = line;
				}
				reader.close();
				
				// Was it optical disc?
				if(opticalMediaType != null)
				{
					synchronized(nowProcessing)
					{
						nowProcessing.add(file);
					}
					SwingUtilities.invokeLater(new Runnable()
					{
						@Override
						public void run()
						{
							setInfo();
						}
					});
					return new VolumeInfo(file, opticalMediaType);
				}
				else if(new File(mountPoint).equals(file))
				{
					knownNotOptical.add(file);
				}
				else
				{
					// Note: when run immediately after disc insert, diskutil sometimes
					// returns details for a different volume (main HD), which is stupid
					// but there we go. We detect this by using the mount point. If the
					// mountpoint doesn't match, we don't store the information and try
					// again in another 500ms.
				}
			}
			return null;
		}
	}
	
	private List<DisplayBit> displayBits = new LinkedList<DisplayBit>();
	
	/**
	 * Handles display for a single disc. Not thread-safe, must be accessed
	 * from Swing thread only.
	 */
	private class DisplayBit
	{
		private boolean finished, error;
		
		private JLabel label;
		private JLabel name;
		private JProgressBar progress;
		private JLabel type;
		private DefaultListModel listModel;
		
		DisplayBit(VolumeInfo volume)
		{
			// See if there's a spare space
			boolean got = false;
			for(ListIterator<DisplayBit> i = displayBits.listIterator(); i.hasNext();)
			{
				DisplayBit existing = i.next();
				if(existing.finished)
				{
					i.remove();
					i.add(this);
					takeOver(existing);
					got = true;
					break;
				}
			}
			
			// If there wasn't, then add one at the end
			if(!got)
			{
				displayBits.add(this);
				label = new JLabel();
				name = new JLabel();
				type = new JLabel();
				type.setOpaque(false);
				Color full = type.getForeground();
				type.setForeground(new Color(full.getRed(), full.getGreen(), full.getBlue(), 128));
				name.setForeground(SystemColor.controlHighlight);
				progress = new JProgressBar();
				listModel = new DefaultListModel();
				
				JPanel container = new JPanel(new BorderLayout(0, 4));
				container.setBorder(
					BorderFactory.createCompoundBorder(
						BorderFactory.createLineBorder(SystemColor.controlHighlight, 4),
						BorderFactory.createEmptyBorder(8, 8, 8, 8)));
				JPanel identifier = new JPanel(new BorderLayout(8, 0));
				identifier.add(type, BorderLayout.EAST);
				identifier.add(label, BorderLayout.CENTER);
				container.add(identifier, BorderLayout.NORTH);
				
				JPanel inner1 = new JPanel(new BorderLayout(0, 4));
				container.add(inner1, BorderLayout.CENTER);
				inner1.add(name, BorderLayout.NORTH);
				
				JPanel inner2 = new JPanel(new BorderLayout(0, 4));
				inner1.add(inner2, BorderLayout.CENTER);
				inner2.add(progress, BorderLayout.NORTH);
				
				JList list = new JList(listModel);
				list.setVisibleRowCount(5);
				JScrollPane pane = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
					JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
				inner2.add(pane, BorderLayout.CENTER);
				
				nextAddPanel.add(container, BorderLayout.NORTH);
				JPanel newPanel = new JPanel(new BorderLayout(0, 8));
				nextAddPanel.add(newPanel, BorderLayout.CENTER);
				nextAddPanel = newPanel;
			}
			
			// Set initial values
			label.setText(volume.getVolume().getName());
			type.setText(volume.getType());
			name.setText("Scanning folders...");
			progress.setIndeterminate(true);
			progress.setVisible(true);
			pack();
		}
		
		private int doneCount;
		
		private void processingFiles(int count)
		{
			progress.setValue(0);
			progress.setMaximum(count);
			progress.setIndeterminate(false);
			doneCount = -1;
		}
		
		private void processingFile(File file)
		{
			doneCount++;
			if(doneCount > 0)
			{
				progress.setValue(doneCount);
			}
			name.setText(file.getName());
		}
		
		private void takeOver(DisplayBit existing)
		{
			this.label = existing.label;
			this.progress = existing.progress;
			this.name = existing.name;
			this.type = existing.type;
			this.listModel = existing.listModel;
		}
		
		private void ejecting()
		{
			name.setText("Ejecting...");
		}
		
		private void finish()
		{
			name.setText(error ? "Finished with errors" : "Finished OK");
			progress.setVisible(false);
			pack();
			if(!error)
			{
				this.finished = true;
			}
		}
		
		private void error(String message)
		{
			listModel.addElement(message);
			error = true;
		}
	}
	
	
	private class ProcessDiscThread extends Thread
	{
		private VolumeInfo volume;
		private DisplayBit display;
		
		private ProcessDiscThread(final VolumeInfo volume)
		{
			this.volume = volume;
			start();
		}
		
		@Override
		public void run()
		{
			SwingUtilities.invokeLater(new Runnable()
			{
				@Override
				public void run()
				{
					display = new DisplayBit(volume);
				}
			});
			
			checkDisc();
			ejectDisc();
		}

		private void checkDisc()
		{
			// List all files
			LinkedList<File> allFiles = new LinkedList<File>();
			listFiles(volume.getVolume(), allFiles);
			
			// Set display status
			final int count = allFiles.size();
			SwingUtilities.invokeLater(new Runnable()
			{
				@Override
				public void run()
				{
					display.processingFiles(count);
				}
			});
			
			// Read all files
			for(File file : allFiles)
			{
				checkFile(file);
			}
		}
		
		private void checkFile(final File file)
		{
			try
			{
				SwingUtilities.invokeLater(new Runnable()
				{
					@Override
					public void run()
					{
						display.processingFile(file);
					}
				});
				
				byte[] buffer = new byte[65536];
				InputStream input = new FileInputStream(file);
				try
				{
					while(true)
					{
						int result = input.read(buffer);
						if(result == -1)
						{
							break;
						}
					}
				}
				finally
				{
					input.close();
				}
			}
			catch(Throwable t)
			{
				error("Error reading: " + getAbbreviatedPath(file));
			}
		}

		/**
		 * @param file File within volume
		 * @return Path without volume bit
		 */
		private String getAbbreviatedPath(final File file)
		{
			String path = file.getPath();
			String volumeBit = volume.getVolume().getPath() + "/";
			if(path.startsWith(volumeBit))
			{
				path = path.substring(volumeBit.length());
			}
			return path;
		}
		
		private void listFiles(File parent, List<File> list)
		{
			try
			{
				File[] files = parent.listFiles();
				if(files == null)
				{
					files = new File[0];
				}
				for(File file : files)
				{
					if(file.isFile())
					{
						list.add(file);
					}
					else if(file.isDirectory())
					{
						listFiles(file, list);
					}
				}
			}
			catch(Throwable t)
			{
				// Note: listFiles claims it can't throw an exception, but let's catch
				// anyhow.
				error("Error scanning: " + getAbbreviatedPath(parent));
			}
		}
		
		private void ejectDisc()
		{
			try
			{
				SwingUtilities.invokeLater(new Runnable()
				{
					@Override
					public void run()
					{
						display.ejecting();
					}
				});
				Process process = Runtime.getRuntime().exec(
					new String[] {"diskutil", "eject", volume.getVolume().getPath()});
				process.waitFor();
				synchronized(nowProcessing)
				{
					nowProcessing.remove(volume.getVolume());
				}
				SwingUtilities.invokeLater(new Runnable()
				{
					@Override
					public void run()
					{
						setInfo();
						display.finish();
					}
				});
			}
			catch(Throwable t)
			{
				error("Error ejecting disc");
			}
		}
		
		private void error(final String message)
		{
			SwingUtilities.invokeLater(new Runnable()
			{
				@Override
				public void run()
				{
					display.error(message);
				}
			});
		}
	}
	
	private void error(Throwable t)
	{
		t.printStackTrace();
	}
	
	/**
	 * Main method just opens frame
	 * @param args Ignored
	 */
	public static void main(String[] args)
	{
		SwingUtilities.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				new Check();
			}
		});
	}

}
