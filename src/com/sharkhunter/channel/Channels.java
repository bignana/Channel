package com.sharkhunter.channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;

import net.pms.PMS;
import net.pms.dlna.DLNAResource;
import net.pms.dlna.virtual.VirtualFolder;
import no.geosoft.cc.io.FileListener;
import no.geosoft.cc.io.FileMonitor;

public class Channels extends VirtualFolder implements FileListener {

	// Version string
	public static final String VERSION="1.03";
	
	// Constants for RTMP string constructions
	public static final int RTMP_MAGIC_TOKEN=1;
	public static final int RTMP_DUMP=2;
	public int rtmp;
	
	public static final int DeafultContLim=5;
	public static final int ContSafetyVal=-100;
	
    private File file;
    private FileMonitor fileMonitor;
    private ArrayList<File> chFiles;
    private ArrayList<ChannelMacro> macros;
    private ArrayList<ChannelCred> cred;
    private HashMap<String,ChannelMacro> scripts;
    private HashMap<String,ChannelSubs> subtitles;
    private ChannelDbg dbg;
    private static Channels inst=null;
    private String savePath;
    private boolean appendTS;
    private boolean subs;
    private ChannelCache cache;
    private boolean doCache;
    private ChannelOffHour oh;
    private ChannelCfg cfg;
    
    public Channels(String path,long poll) {
    	super("Channels",null);
    	this.file=new File(path);
    	inst=this;
    	subs=true;
    	doCache=false;
    	chFiles=new ArrayList<File>();
    	cred=new ArrayList<ChannelCred>();
    	scripts=new HashMap<String,ChannelMacro>();
    	subtitles=new HashMap<String,ChannelSubs>();
    	cache=new ChannelCache(path);
    	savePath="";
    	oh=null;
    	appendTS=false;
    	//rtmp=Channels.RTMP_MAGIC_TOKEN;
    	rtmp=Channels.RTMP_DUMP;
    	PMS.minimal("Start channel "+VERSION);
    	dbg=new ChannelDbg(new File(path+File.separator+"channel.log"));
    	addChild(cache);
    	fileMonitor=null;
    	if(poll>0)
    		fileMonitor=new FileMonitor(poll);
    	fileChanged(file);
    	if(poll>0) {
    		fileMonitor.addFile(file);
    		fileMonitor.addListener(this);
    	}
    }
    
    public static void debug(String msg) {
    	inst.dbg.debug("[Channel] "+msg);
    }
    
    public static void debug(boolean start) {
    	if(start)
    		inst.dbg.start();
    	else
    		inst.dbg.stop();
    }
    
    public static boolean debugStatus() {
    	return inst.dbg.status();
    }
    
    public static File dbgFile() {
    	return inst.dbg.logFile();
    }
    
    private Channel find(String name) {
    	for(DLNAResource f:children)
    		if((f instanceof Channel)&&(f.getDisplayName().equals(name)))
    				return (Channel) f;
    	return null;
    }
    
    private void readChannel(String data)  throws Exception {
    	String str;
    	String[] lines=data.split("\n");
    	for(int i=0;i<lines.length;i++) {
    	    str=lines[i].trim();
    	    if(str.startsWith("macrodef ")) {
    	    	ArrayList<String> mData=ChannelUtil.gatherBlock(lines, i+1);
    	    	i+=mData.size();
    	    	continue;
    	    }
    	    if(str.startsWith("channel ")) {
    			String chName=str.substring(8,str.lastIndexOf('{')).trim();
    			ArrayList<String> chData=ChannelUtil.gatherBlock(lines, i+1);
    			i+=chData.size();
    			Channel old=find(chName);
    			if(old!=null) {
    				old.parse(chData,macros);
    			}
    			else {
    				Channel ch=new Channel(chName);
    				if(ch.Ok) {
    					ch.parse(chData,macros);
    					addChild(ch);
    				}	
    				else {
    					PMS.minimal("channel "+chName+" was not parsed ok");
    				}
    			}
    		}
    	}
    }
    
    private void parseDefines(String data) {
    	String str;
    	String[] lines=data.split("\n");
    	macros=new ArrayList<ChannelMacro>();
    	for(int i=0;i<lines.length;i++) {
    	    str=lines[i].trim();
    	    if(str.startsWith("macrodef ")) {
    	    	String mName=str.substring(9,str.lastIndexOf('{')).trim();
    	    	ArrayList<String> mData=ChannelUtil.gatherBlock(lines, i+1);
    	    	i+=mData.size();
    	    	macros.add(new ChannelMacro(mName,mData));
    	    	continue;
    	    }
    	    if(str.startsWith("scriptdef ")) {
    	    	String sName=str.substring(10,str.lastIndexOf('{')).trim();
    	    	ArrayList<String> sData=ChannelUtil.gatherBlock(lines, i+1);
    	    	i+=sData.size();
    	    	if(scripts.get(sName)!=null) {
    	    		debug("Duplicate definition of script "+sName+" found. Ignore this one.");
    	    		continue;
    	    	}
    	    	scripts.put(sName, new ChannelMacro(sName,sData));
    	    	continue;
    	    }
    	    if(str.startsWith("subdef ")) {
    	    	String sName=str.substring(7,str.lastIndexOf('{')).trim();
    	    	ArrayList<String> sData=ChannelUtil.gatherBlock(lines, i+1);
    	    	i+=sData.size();
    	    	if(subtitles.get(sName)!=null)
    	    		continue;
    	    	subtitles.put(sName, new ChannelSubs(sName,sData,file));
    	    	continue;
    	    }
    	}
    }
    
    public void parseChannels(File f)  throws Exception {
    	BufferedReader in=new BufferedReader(new FileReader(f));
    	String str;
    	boolean defines=false;
    	StringBuilder sb=new StringBuilder();
    	String ver="unknown";    	
    	while ((str = in.readLine()) != null) {
    		str=str.trim();
    		if(ChannelUtil.ignoreLine(str))
				continue;
    	    if(str.trim().startsWith("macrodef"))
    	    	defines=true;
    	    if(str.trim().startsWith("scriptdef"))
    	    	defines=true;
    	    if(str.trim().startsWith("subdef"))
    	    	defines=true;
    	    if(str.trim().startsWith("version")) {
    	    	String[] v=str.split("\\s*=\\s*");
    	    	if(v.length<2)
    	    		continue;
    	    	ver=v[1];
    	    	continue; // don't append these
    	    }	
    	    sb.append(str);
    	    sb.append("\n");
    	}
    	in.close();
    	PMS.minimal("parsing channel file "+f.toString()+" version "+ver);
    	debug("parsing channel file "+f.toString()+" version "+ver);
    	String s=sb.toString();
    	if(defines)
    		parseDefines(s);
    	readChannel(s);
    	addCred();
    }
    
    
    private void addCred() {
    	for(int i=0;i<cred.size();i++) {
    		ChannelCred cr=cred.get(i);
    		Channel ch=find(cr.channelName);
    		if(ch==null)
    			continue;
    		cr.ch=ch;
    		debug("adding cred to channel "+cr.channelName);
    		ch.addCred(cr);
    	}
    		
    }
    
    private void handleCred(File f)  {
    	BufferedReader in;
		try {
			in = new BufferedReader(new FileReader(f));
			String str;
			while ((str = in.readLine()) != null) {
				str=str.trim();
				if(ChannelUtil.ignoreLine(str))
					continue;
				String[] s=str.split("\\s*=\\s*",2);
				if(s.length<2)
					continue;
				String[] s1=s[0].split("\\.");
				if(s1.length<2)
					continue;
				if(!s1[0].equalsIgnoreCase("channel"))
					continue;
				String[] s2=s[1].split(",");
				if(s2.length<2)
					continue;
				String chName=s1[1];
				ChannelCred ch=null;
				for(int i=0;i<cred.size();i++)
					if(cred.get(i).channelName.equals(chName)) {
						ch=cred.get(i);
						break;
					}
				if(ch==null) {
					ch=new ChannelCred(s2[0],s2[1],chName);
					cred.add(ch);
				}
				ch.user=s2[0];
				ch.pwd=s2[1];
			}
			addCred();
		}
    	catch (Exception e) {} 
    }
    
    private void handleVCR(File f) throws IOException {
    		BufferedReader in = new BufferedReader(new FileReader(f));
    		SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			String str;
			GregorianCalendar now=new GregorianCalendar();
			while ((str = in.readLine()) != null) {
				str=str.trim();
				if(ChannelUtil.ignoreLine(str))
					continue;
				String[] data=str.split(",");
				if(data.length<2)
					continue;
				Date d;
				String name="";
				try {
					d=sdfDate.parse(data[0]);
				} catch (ParseException e) {
					debug("bad date format "+str);
					continue;
				}
				if(d.before(now.getTime())) {
					debug("Time already past "+str);
					continue;
				}
				String proc="";
				if(data.length>2)
					proc=data[2];
				if(data.length>3)
					name=data[3];
				ChannelVCR vcr=new ChannelVCR(d,data[1],proc,name);
			}
    }
    
    private void handleDirChange(File dir) throws Exception {
    	if(!dir.exists()) // file (or dir is gone) ignore
			return;
    	File[] files=dir.listFiles();
		for(int i=0;i<files.length;i++) {
			File f=files[i];
			if(f.getAbsolutePath().endsWith(".ch")) { // only bother about ch-files
				if(!chFiles.contains(f)) { // new file
					try {
						parseChannels(f);
						chFiles.add(f);
						if(fileMonitor!=null)
							fileMonitor.addFile(f);
					} catch (Exception e) {
						PMS.minimal("Error parsing file "+f.toString()+" ("+e.toString()+")");
						//e.printStackTrace();
					}	
				}
			}
			else if(f.getAbsolutePath().endsWith(".cred"))
				handleCred(f);
			else if(f.getAbsolutePath().endsWith(".vcr"))
				handleVCR(f);
			
		}	
    }

	@Override
	public void fileChanged(File f) {
		if(f.isDirectory()) { // directory modified, new file or file gone?
			try {
				handleDirChange(f);
			} catch (Exception e) {
			}
		}
		else { // file change
			try {
				if(f.getAbsolutePath().endsWith(".cred"))
					handleCred(f);
				else if(f.getAbsolutePath().endsWith(".vcr"))
					handleVCR(f);
				else
					if(f.exists())
						parseChannels(f);
			} catch (Exception e) {
				PMS.minimal("Error parsing file "+f.toString()+" ("+e.toString()+")");
				//e.printStackTrace();
			}	
		}
	}
	
	//////////////////////////////////////

	private int convInt(String str,int def) {
		try {
			Integer i=Integer.valueOf(str);
			return i.intValue();
		}
		catch (Exception e) {
		}
		return def;
	}
	
	private void startOffHour() {
		String cfg=(String) PMS.getConfiguration().getCustomProperty("channels.offhour");
		if(ChannelUtil.empty(cfg)) // no config, nothing to do
			return;
		String ohDb=file.getAbsolutePath()+File.separator+"data"+File.separator+"offhour";
		String[] s=cfg.split(",");
		int max=ChannelOffHour.DEFAULT_MAX_THREAD;
		int dur=ChannelOffHour.DEFAULT_MAX_DURATION;
		boolean cache=false;
		if(s.length>1)
			dur=convInt(s[1],dur);
		if(s.length>2)
			max=convInt(s[2],max);
		if(s.length>3&&s[3].equalsIgnoreCase("cache"))
			cache=true;
		oh=new ChannelOffHour(max,dur,s[0],new File(ohDb),cache);
		oh.init();
	}
	
	////////////////////////////////////
	// Save handling
	////////////////////////////////////
	
	public void setSave(String sPath) {
		setSave(sPath,null);
		
	}
	
	public void setSave(String sPath,String ts) {
		savePath=sPath;
		appendTS=(ChannelUtil.empty(ts)?false:true);
		cache.savePath(sPath);
		if(oh==null) 
			startOffHour();
		PMS.debug("[Channel]: using save path "+sPath);
		debug("using save path "+sPath);
	}
	
	public static boolean save() {
		return !ChannelUtil.empty(inst.savePath);
	}
	
	public static String fileName(String name,boolean cache) {
		String ts="";
		name=name.trim();
		String ext=ChannelUtil.extension(name);
		if(inst.appendTS) 
			ts="_"+String.valueOf(System.currentTimeMillis());
		String fName=ChannelUtil.append(name, null, ts);
		fName=ChannelUtil.append(fName,null, ext);
		// if we got an extension we move it to the end of the filename
		if(!cache&&save())
			return cfg().getSavePath()+File.separator+fName;
		else
			return getPath()+File.separator+"data"+File.separator+fName;
	}
	
	///////////////////////////////////////////
	// Path handling
	///////////////////////////////////////////
	
	public static String getSavePath() {
		return inst.savePath;
	}
	
	public static String getPath() {
		return inst.file.getAbsolutePath();
	}
	
	public void setPath(String path) {
		debug("Set chanelpath to "+path);
		file=new File(path);
	}
	
	////////////////////////////////////////////
	// Script functions
	////////////////////////////////////////////
	
	public static ArrayList<String> getScript(String name) {
		ChannelMacro m=inst.scripts.get(name);
		if(m!=null) { // found a script return data
			return m.getMacro();
		}
		return null;
	}
	
	public static ChannelSubs getSubs(String name) {
		return inst.subtitles.get(name);
	}
	
	/////////////////////////////////
	// RtmpMethod change
	/////////////////////////////////
	
	public static void rtmpMethod(int newVal) {
		inst.rtmp=newVal;
	}
	
	public static int rtmpMethod() {
		return inst.rtmp;
	}
	
	public static void setSubs(boolean b) {
		inst.subs=b;
	}
	
	public static boolean doSubs() {
		return inst.subs;
	}
	
	public static boolean cache() {
		return inst.doCache;
	}
	
	public static void setCache(boolean b) {
		inst.doCache=true;
	}
	
	public static String cacheFile() {
		return inst.file.getAbsolutePath()+File.separator+"data"+File.separator+"cache";
	}
	
	public static ChannelOffHour getOffHour() {
		return inst.oh;
	}
	
	public static ChannelCfg cfg() {
		return inst.cfg;
	}
	
	public void setCfg(ChannelCfg c) {
		cfg=c;
	}
	
	
}
