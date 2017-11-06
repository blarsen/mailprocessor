package net.nonews.util;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.mail.*;
import javax.mail.Flags.Flag;
import javax.mail.search.FlagTerm;

import com.sun.mail.imap.*;


public class MailProcessor {
	private static Logger logger = Logger.getLogger(MailProcessor.class.getName());

	Properties settings = new Properties();

	Store store = null;

	private String getSetting(String key) {
		return settings.getProperty(key);
	}

        private String getSetting(String key, String def) {
	    return settings.getProperty(key, def);
	}

	private void loadSettings(String fileName) {
		FileInputStream fis;
		try {
			fis = new FileInputStream(new File(fileName));
			settings.load(fis);
			debug("Settings loaded from "+fileName);
		} catch (FileNotFoundException e) {
			error("Could not read settings file " + fileName + ": " + e.toString() + " Program ends", e);
			System.exit(0);
		} catch (IOException e) {
			error("Error while reading settings file: " + e.toString() + ". Program ends", e);
			System.exit(0);
		}
	}

	private void debug(String message) {
		logger.log(Level.FINE, message);
	}

	private void error(String message, Exception e) {
		logger.log(Level.SEVERE, message, e);
	}


	private void run(String targetName) {
		logger.info("Starting");
		loadSettings(targetName+"/MailProcessor.properties");

		// Get a Properties object
		Properties props = System.getProperties();

		props.put("mail.smtp.startls.enable", "true");

		// Get a Session object
		Session session = Session.getInstance(props, null);
		session.setDebug(getBooleanSetting("debug"));

		// Get a Store object
		Folder rf = null;
		try {
			store = session.getStore("imaps");
		} catch (NoSuchProviderException e) {
			error("No such provider: "+e.toString(),e);
			return;
		}

		// Connect
		try {
		    store.connect(getSetting("imapHost"), new Integer(getSetting("imapPort", "143")), getSetting("imapUser"), getSetting("imapPassword"));
		} catch (MessagingException e) {
			error("Messaging exception on connect: "+e.toString(), e);
			return;
		}

		String folderName = getSetting("imapFolder");
		try {
			if (folderName == null)
				rf = store.getDefaultFolder();
			else
				rf = store.getFolder(folderName);
		} catch (MessagingException e) {
			error("Messaging exception getting folder: "+e.toString(), e);
			return;
		}

		/*
		debug("Folder: "+rf);
		try {
			dumpFolder(rf, true, "   ");
		} catch (Exception e) {
			error("Ouch in dumpfolder", e);
		}
		*/

		processAllRead(rf);

		logger.info("Done");
	}

	private void processAllRead(Folder folder) {
		try {
			folder.open(Folder.READ_WRITE);
			Message[] read = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), true));

			for (int i = 0; i < read.length; i++) {
				Message m = read[i];
				if (!m.getFlags().contains(Flags.Flag.DELETED) && !m.isExpunged()) // Skip messages marked as deleted or expunged
					processMessage(folder, m);
			}
			// Close folder and expunge deleted messages
	      		folder.close(true);

		} catch (MessagingException e) {
			error("Messaging exception processing unread messages: "+e, e);
			return;
		}		
	}

	private void processMessage(Folder f, Message m) {
		int idx = 1;
		while(getSetting("header"+idx) != null) {
			String header = getSetting("header"+idx);
			String pattern = getSetting("pattern"+idx);
			String destination = getSetting("destination"+idx);

			try {
				if (headerMatch(m, header, pattern)) {
					logger.info("Should move this message to "+destination+":");
					logger.info(headerFormat("   From: ", m.getHeader("From")));
					logger.info(headerFormat("   Subject: ", m.getHeader("Subject")));

					Folder target = store.getFolder(destination);
					Message[] msgs = new Message[1];
					msgs[0] = m;
					f.copyMessages(msgs, target);
					m.setFlag(Flag.DELETED, true);
					// TODO: Remove message from current folder..
				}
			} catch (MessagingException e) {
				error("Ouch!",e);
			}
			idx++;

		}

	}

	private static String headerFormat(String headerField, String[] header) {
		if (header == null)
			header = new String[0];

		String result = headerField;
		for (int i = 0; i < header.length; i++) {
			if (i > 0)
				result += "\n\t";
			result += header[i];
		}
		return result;

	}

	private boolean headerMatch(Message m, String headerField, String regexp) {

		String[] header;
		try {
			header = m.getHeader(headerField);
			if (header == null)
				return false;
			for (int i = 0; i < header.length; i++) {
				String hi = header[i].replaceAll("[\\n\\r\\t]+", " ");  // Embedded newlines mess up RE matching
				if (hi.matches(regexp))
					return true;
			}
			return false;
		} catch (MessagingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}

	static void dumpFolder(Folder folder, boolean recurse, String tab)
	throws Exception {
		System.out.println(tab + "Name:      " + folder.getName());
		System.out.println(tab + "Full Name: " + folder.getFullName());
		System.out.println(tab + "URL:       " + folder.getURLName());

		boolean verbose = true;
		if (verbose) {
			if (!folder.isSubscribed())
				System.out.println(tab + "Not Subscribed");

			if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
				if (folder.hasNewMessages())
					System.out.println(tab + "Has New Messages");
				System.out.println(tab + "Total Messages:  " +
						folder.getMessageCount());
				System.out.println(tab + "New Messages:    " +
						folder.getNewMessageCount());
				System.out.println(tab + "Unread Messages: " +
						folder.getUnreadMessageCount());
			}
			if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0)
				System.out.println(tab + "Is Directory");

			/*
			 * Demonstrate use of IMAP folder attributes
			 * returned by the IMAP LIST response.
			 */
			if (folder instanceof IMAPFolder) {
				IMAPFolder f = (IMAPFolder)folder;
				String[] attrs = f.getAttributes();
				if (attrs != null && attrs.length > 0) {
					System.out.println(tab + "IMAP Attributes:");
					for (int i = 0; i < attrs.length; i++)
						System.out.println(tab + "    " + attrs[i]);
				}
			}
		}

		System.out.println();

		if ((folder.getType() & Folder.HOLDS_FOLDERS) != 0) {
			if (recurse) {
				Folder[] f = folder.list();
				for (int i = 0; i < f.length; i++)
					dumpFolder(f[i], recurse, tab + "    ");
			}
		}
	}


	private boolean getBooleanSetting(String key) {
		String val = getSetting(key);
		if (val == null)
			return false;
		return (val.equals("true"));
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
	    String targetName = args[0];

		MailProcessor me = new MailProcessor();
		me.run(targetName);
	}

}
