/*
 * Copyright (c) 1998-2011 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.boot;

import com.caucho.VersionFactory;
import com.caucho.config.ConfigException;
import com.caucho.license.*;
import com.caucho.server.resin.ResinELContext;
import com.caucho.server.util.CauchoSystem;
import com.caucho.util.L10N;
import com.caucho.vfs.Path;
import com.caucho.vfs.Vfs;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

class WatchdogArgs
{
  private static L10N _L;
  private static final Logger log
    = Logger.getLogger(WatchdogArgs.class.getName());

  private Path _javaHome;
  private Path _resinHome;
  private Path _rootDirectory;
  private Path _dataDirectory;
  private String[] _argv;
  private Path _resinConf;
  private Path _logDirectory;
  private String _serverId = "";
  private String _clusterId;
  private int _watchdogPort;
  private boolean _isVerbose;
  private boolean _isHelp;
  private StartMode _startMode;

  private ArrayList<String> _tailArgs = new ArrayList<String>();

  private boolean _isDynamicServer;
  private String _dynamicCluster;
  private String _dynamicAddress;
  private int _dynamicPort;

  private boolean _is64bit;

  WatchdogArgs(String[] argv)
  {
    this(argv, true);
  }

  WatchdogArgs(String[] argv, boolean isTop)
  {
    String logLevel = System.getProperty("resin.log.level");

    if (isTop)
      setLogLevel(logLevel);

    _resinHome = calculateResinHome();
    _rootDirectory = calculateResinRoot(_resinHome);

    _javaHome = Vfs.lookup(System.getProperty("java.home"));

    _argv = fillArgv(argv);

    _resinConf = _resinHome.lookup("conf/resin.conf");
    if (! _resinConf.canRead())
      _resinConf = _resinHome.lookup("conf/resin.xml");

    _is64bit = CauchoSystem.is64Bit();

    parseCommandLine(_argv);
  }

  String []getRawArgv()
  {
    return _argv;
  }

  Path getJavaHome()
  {
    return _javaHome;
  }

  Path getResinHome()
  {
    return _resinHome;
  }

  Path getRootDirectory()
  {
    return _rootDirectory;
  }

  Path getDataDirectory()
  {
    return _dataDirectory;
  }

  Path getLogDirectory()
  {
    return _logDirectory;
  }

  Path getResinConf()
  {
    return _resinConf;
  }

  String getServerId()
  {
    return _serverId;
  }

  String getClusterId()
  {
    return _clusterId;
  }

  String[] getArgv()
  {
    return _argv;
  }

  boolean isDynamicServer()
  {
    return _isDynamicServer;
  }

  String getDynamicCluster()
  {
    return _dynamicCluster;
  }

  String getDynamicAddress()
  {
    if (_dynamicAddress != null)
      return _dynamicAddress;
    else {
      try {
        return InetAddress.getLocalHost().getHostAddress();
      } catch (Exception e) {
        return null;
      }
    }
  }

  int getDynamicPort()
  {
    if (_dynamicPort > 0)
      return _dynamicPort;
    else
      return 6830;
  }

  String getDynamicServerId()
  {
    if (_serverId != null)
      return _serverId;
    else
      return "dyn-" + getDynamicAddress() + "-" + getDynamicPort();
  }

  boolean isVerbose()
  {
    return _isVerbose;
  }

  void setWatchdogPort(int port)
  {
    _watchdogPort = port;
  }

  int getWatchdogPort()
  {
    return _watchdogPort;
  }

  void setResinHome(Path resinHome)
  {
    _resinHome = resinHome;
  }

  boolean is64Bit()
  {
    return _is64bit;
  }

  boolean isStatus()
  {
    return _startMode == StartMode.STATUS;
  }

  boolean isStart()
  {
    return _startMode == StartMode.START;
  }

  boolean isGui()
  {
    return _startMode == StartMode.GUI;
  }

  boolean isStop()
  {
    return _startMode == StartMode.STOP;
  }

  boolean isRestart()
  {
    return _startMode == StartMode.RESTART;
  }

  boolean isKill()
  {
    return _startMode == StartMode.KILL;
  }

  boolean isShutdown()
  {
    return _startMode == StartMode.SHUTDOWN;
  }

  boolean isConsole()
  {
    return _startMode == StartMode.CONSOLE;
  }

  boolean isWatchdogConsole()
  {
    return _startMode == StartMode.START_WITH_FOREGROUND;
  }

  StartMode getStartMode()
  {
    return _startMode;
  }

  public ArrayList<String> getTailArgs()
  {
    return _tailArgs;
  }

  public String getArg(String arg)
  {
    for (int i = 0; i + 1 < _argv.length; i++) {
      if (_argv[i].equals(arg)
          || _argv[i].equals("-" + arg))
        return _argv[i + 1];
    }

    return null;
  }

  public String getArgFlag(String arg)
  {
    for (int i = 0; i < _argv.length; i++) {
      if (_argv[i].equals(arg)
          || _argv[i].equals("-" + arg))
        return _argv[i];
      
      else if (_argv[i].startsWith(arg + "=")) {
        return _argv[i].substring(arg.length() + 1);
      }
      else if (_argv[i].startsWith("-" + arg + "=")) {
        return _argv[i].substring(arg.length() + 2);
      }
    }

    return null;
  }
  
  public boolean getArgBoolean(String arg, boolean defaultValue)
  {
    String value = getArgFlag(arg);

    if (value == null)
      return defaultValue;
    
    if ("no".equals(value) || "false".equals(value))
      return false;
    else
      return true;
  }

  public boolean hasOption(String option)
  {
    for (String arg : _argv) {
      if (option.equals(arg))
        return true;
    }

    return false;
  }

  public ResinELContext getELContext()
  {
    return new ResinBootELContext();
  }

  /**
   * finds first argument that follows no dash prefixed token
   * @return
   */
  public String getDefaultArg()
  {
    ArrayList<String> tailArgs = getTailArgs();

    for (int i = 0; i < tailArgs.size(); i++) {
      String arg = tailArgs.get(i);

      if (arg.startsWith("-")) {
        i++;
        continue;
      }

      return arg;
    }

    return null;
  }

  /**
   * returns all trailing following no dash prefixed token args
   */
  public String []getTrailingArgs(Set<String> options)
  {
    LinkedList<String> result = new LinkedList<String>();
    ArrayList<String> tailArgs = getTailArgs();

    for (int i = tailArgs.size() - 1; i >= 0; i--) {
      String arg = tailArgs.get(i);

      if (! arg.startsWith("-")) {
        result.addFirst(arg);
      }
      else if (options.contains(arg)) {
        break;
      }
      else if (! result.isEmpty()) {
        result.removeFirst();
      }
    }

    return result.toArray(new String[result.size()]);
  }

  public boolean isHelp()
  {
    return _isHelp;
  }

  private void setLogLevel(String levelName)
  {
    Level level = Level.INFO;

    if ("off".equals(levelName))
      level = Level.OFF;
    else if ("all".equals(levelName))
      level = Level.ALL;
    else if ("severe".equals(levelName))
      level = Level.SEVERE;
    else if ("warning".equals(levelName))
      level = Level.WARNING;
    else if ("info".equals(levelName))
      level = Level.INFO;
    else if ("config".equals(levelName))
      level = Level.CONFIG;
    else if ("fine".equals(levelName))
      level = Level.FINE;
    else if ("finer".equals(levelName))
      level = Level.FINER;
    else if ("finest".equals(levelName))
      level = Level.FINEST;

    Logger.getLogger("").setLevel(level);
  }

  private void parseCommandLine(String[] argv)
  {
    String resinConf = null;

    for (int i = 0; i < argv.length; i++) {
      String arg = argv[i];

      if ("-conf".equals(arg) || "--conf".equals(arg)) {
        resinConf = argv[i + 1];
        i++;
      }
      else if ("-join-cluster".equals(arg)
               || "--join-cluster".equals(arg)) {
        _isDynamicServer = true;
        _dynamicCluster = argv[i + 1];

        i++;
      }
      else if ("-fine".equals(arg) || "--fine".equals(arg)) {
        _isVerbose = true;
        Logger.getLogger("").setLevel(Level.FINE);
      }
      else if ("-finer".equals(arg) || "--finer".equals(arg)) {
        _isVerbose = true;
        Logger.getLogger("").setLevel(Level.FINER);
      }
      else if ("-log-directory".equals(arg) || "--log-directory".equals(arg)) {
        _logDirectory = _rootDirectory.lookup(argv[i + 1]);
        i++;
      }
      else if ("-resin-home".equals(arg) || "--resin-home".equals(arg)) {
        _resinHome = Vfs.lookup(argv[i + 1]);
        argv[i + 1] = _resinHome.getFullPath();
        i++;
      }
      else if ("-root-directory".equals(arg) || "--root-directory".equals(arg)) {
        _rootDirectory = Vfs.lookup(argv[i + 1]);
        argv[i + 1] = _rootDirectory.getFullPath();
        i++;
      }
      else if ("-data-directory".equals(arg) || "--data-directory".equals(arg)) {
        _dataDirectory = Vfs.lookup(argv[i + 1]);
        argv[i + 1] = _dataDirectory.getFullPath();
        i++;
      }
      else if ("-server".equals(arg) || "--server".equals(arg)) {
        _serverId = argv[i + 1];
        i++;
      }
      else if ("-cluster".equals(arg) || "--cluster".equals(arg)) {
        _clusterId = argv[i + 1];
        i++;
      }
      else if ("-server-root".equals(arg) || "--server-root".equals(arg)) {
        _rootDirectory = Vfs.lookup(argv[i + 1]);
        argv[i + 1] = _rootDirectory.getFullPath();
        i++;
      }
      else if ("-stage".equals(arg) || "--stage".equals(arg)) {
        // skip stage
        i++;
      }
      else if ("-preview".equals(arg) || "--preview".equals(arg)) {
        // pass to server
      }
      else if ("-watchdog-port".equals(arg) || "--watchdog-port".equals(arg)) {
        _watchdogPort = Integer.parseInt(argv[i + 1]);
        i++;
      }
      else if (arg.startsWith("-J")
               || arg.startsWith("-D")
               || arg.startsWith("-X")) {
      }
      else if (arg.equals("-d64")) {
        _is64bit = true;
      }
      else if (arg.equals("-d32")) {
        _is64bit = false;
      }
      else if ("-debug-port".equals(arg) || "--debug-port".equals(arg)) {
        i++;
      }
      else if ("-jmx-port".equals(arg) || "--jmx-port".equals(arg)) {
        i++;
      }
      else if ("--dump-heap-on-exit".equals(arg)) {
      }
      else if ("-verbose".equals(arg) || "--verbose".equals(arg)) {
        _isVerbose = true;
        Logger.getLogger("").setLevel(Level.CONFIG);
      }
      else if ("help".equals(arg)) {
        _isHelp = true;
      }
      else if ("console".equals(arg)) {
        _startMode = StartMode.CONSOLE;
      }
      else if ("deploy".equals(arg)) {
        _startMode = StartMode.DEPLOY;
      }
      else if ("deploy-config".equals(arg)) {
        _startMode = StartMode.DEPLOY_CONFIG;
      }
      else if ("deploy-copy".equals(arg) || "copy".equals(arg)) {
        _startMode = StartMode.DEPLOY_COPY;
      }
      else if ("deploy-copy".equals(arg) || "copy".equals(arg)) {
        _startMode = StartMode.DEPLOY_COPY;
      }
      else if ("deploy-list".equals(arg) || "list".equals(arg)) {
        _startMode = StartMode.DEPLOY_LIST;
      }
      else if ("deploy-restart".equals(arg) || "restart-webapp".equals(arg)) {
        _startMode = StartMode.DEPLOY_RESTART;
      }
      else if ("deploy-start".equals(arg) || "start-webapp".equals(arg)) {
        _startMode = StartMode.DEPLOY_START;
      }
      else if ("deploy-stop".equals(arg) || "stop-webapp".equals(arg)) {
        _startMode = StartMode.DEPLOY_STOP;
      }
      else if ("disable".equals(arg)) {
        _startMode = StartMode.DISABLE;
      }
      else if ("disable-soft".equals(arg)) {
        _startMode = StartMode.DISABLE_SOFT;
      }
      else if ("enable".equals(arg)) {
        _startMode = StartMode.ENABLE;
      }
      else if ("gui".equals(arg)) {
        _startMode = StartMode.GUI;
      }
      else if ("heap-dump".equals(arg)) {
        _startMode = StartMode.HEAP_DUMP;
      }
      else if ("jmx-call".equals(arg)) {
        _startMode = StartMode.JMX_CALL;
      }
      else if ("jmx-list".equals(arg)) {
        _startMode = StartMode.JMX_LIST;
      }
      else if ("jmx-dump".equals(arg)) {
        _startMode = StartMode.JMX_DUMP;
      }
      else if ("jmx-set".equals(arg)) {
        _startMode = StartMode.JMX_SET;
      }
      else if ("jspc".equals(arg)) {
        _startMode = StartMode.JSPC;
      }
      else if ("kill".equals(arg)) {
        _startMode = StartMode.KILL;
      }
      else if ("list-restarts".equals(arg)) {
        _startMode = StartMode.LIST_RESTARTS;
      }
      else if ("log-level".equals(arg)) {
        _startMode = StartMode.LOG_LEVEL;
      }
      else if ("pdf-report".equals(arg)) {
        _startMode = StartMode.PDF_REPORT;
      }
      else if ("profile".equals(arg)) {
        _startMode = StartMode.PROFILE;
      }
      else if ("restart".equals(arg)) {
        _startMode = StartMode.RESTART;
      }
      else if ("thread-dump".equals(arg)) {
        _startMode = StartMode.THREAD_DUMP;
      }
      else if ("shutdown".equals(arg)) {
        _startMode = StartMode.SHUTDOWN;
      }
      else if ("start".equals(arg)) {
        _startMode = StartMode.START;
      }
      else if ("status".equals(arg)) {
        _startMode = StartMode.STATUS;
      }
      else if ("stop".equals(arg)) {
        _startMode = StartMode.STOP;
      }
      else if ("undeploy".equals(arg)) {
        _startMode = StartMode.UNDEPLOY;
      }
      else if ("user-add".equals(arg)) {
        _startMode = StartMode.USER_ADD;
      }
      else if ("user-list".equals(arg)) {
        _startMode = StartMode.USER_LIST;
      }
      else if ("user-remove".equals(arg)) {
        _startMode = StartMode.USER_REMOVE;
      }
      else if ("version".equals(arg)) {
        System.out.println(VersionFactory.getFullVersion());
        System.exit(0);
      }
      else if ("watchdog".equals(arg)) {
        _startMode = StartMode.WATCHDOG;
      }
      else if ("start-with-foreground".equals(arg)) {
        _startMode = StartMode.START_WITH_FOREGROUND;
      }
      else if (_startMode != null) {
        _tailArgs.add(arg);
      }
      else if (_isHelp) {
      }
/*
      else {
        System.out.println(L().l("unknown argument '{0}'", argv[i]));
        System.out.println();
        usage();
        System.exit(1);
      }
*/  //#4605 (support before / after command option placement)
    }

    if (_isHelp && _startMode == null) {
      usage();

      System.exit(1);
    }
    else if (_startMode == null) {
      System.out.println(L().l("Resin requires a command:"
                               + "\n  console - start Resin in console mode"
                               + "\n  status - watchdog status"
                               + "\n  start - start a Resin server"
                               + "\n  start-with-foreground - start Resin server keeping parent JVM (OSX)"
                               + "\n  gui - start a Resin server with a GUI"
                               + "\n  stop - stop a Resin server"
                               + "\n  restart - restart a Resin server"
                               + "\n  kill - force a kill of a Resin server"
                               + "\n  shutdown - shutdown the watchdog"
                               + "\n  deploy - deploys an application"
                               + "\n  deploy-copy - copies an application"
                               + "\n  deploy-list - lists all deployed applications"
                               + "\n  deploy-restart - restarts an application"
                               + "\n  deploy-start - starts an application"
                               + "\n  deploy-stop - stops an application"
                               + "\n  undeploy - undeploys an application"
                               + "\n  heap-dump - produces a heap dump"
                               + "\n  thread-dump - produces a thread dump"
                               + "\n  pdf-report - generates pdf report (Pro version only)"
                               + "\n  profile - profiles the system"
                               + "\n  list-restarts - lists server restart timestamps"
                               + "\n  log-level - sets a log level"
                               + "\n  jmx-list - lists MBeans, attributes, operations"
                               + "\n  jmx-dump - dumps all MBean attributes and values"
                               + "\n  jmx-set - sets value on MBean's attribute"
                               + "\n  jmx-call - invokes a method on MBean"
                               + "\n  user-add - adds an admin user"
                               + "\n  user-remove - removes an admin user"
                               + "\n  user-list - lists admin users"
                               + "\n  help <command> - prints command usage message"
                               + "\n  version - prints version"));
      System.exit(1);
    }

    if (resinConf != null) {
      _resinConf = Vfs.getPwd().lookup(resinConf);

      if (! _resinConf.exists() && _rootDirectory != null)
        _resinConf = _rootDirectory.lookup(resinConf);

      if (! _resinConf.exists() && _resinHome != null)
        _resinConf = _resinHome.lookup(resinConf);

      if (! _resinConf.exists())
        throw new ConfigException(L().l("Resin/{0} can't find configuration file '{1}'", VersionFactory.getVersion(), _resinConf.getNativePath()));
    }
  }

  private static void usage()
  {
    System.err.println(L().l("usage: bin/resin.sh [-options] [console | status | start | gui | stop | restart | kill | shutdown | version | start-with-foreground]"));
    System.err.println(L().l("       bin/resin.sh [-options] [deploy | undeploy | deploy-copy | deploy-list | deploy-start | deploy-stop | deploy-restart]"));
    System.err.println(L().l("       bin/resin.sh [-options] [thread-dump | heap-dump | pdf-report | log-level | profile | jmx-list | jmx-dump | jmx-call | jmx-set]"));
    System.err.println(L().l("       bin/resin.sh [-options] [user-add | user-list | user-remove]"));
    System.err.println(L().l("       bin/resin.sh help <command>"));
    System.err.println(L().l(""));
    System.err.println(L().l("where options include:"));
    System.err.println(L().l("   -conf <file>          : select a configuration file"));
    System.err.println(L().l("   -data-directory <dir> : select a resin-data directory"));
    System.err.println(L().l("   -join <cluster>       : join a cluster as a dynamic server"));
    System.err.println(L().l("   -log-directory <dir>  : select a logging directory"));
    System.err.println(L().l("   -resin-home <dir>     : select a resin home directory"));
    System.err.println(L().l("   -root-directory <dir> : select a root directory"));
    System.err.println(L().l("   -server <id>          : select a <server> to run"));
    System.err.println(L().l("   -watchdog-port <port> : override the watchdog-port"));
    System.err.println(L().l("   -verbose              : print verbose starting information"));
    System.err.println(L().l("   -preview              : run as a preview server"));
    System.err.println(L().l("   -debug-port <port>    : configure a debug port"));
    System.err.println(L().l("   -jmx-port <port>      : configure an unauthenticated jmx port"));

  }

  private String []fillArgv(String []argv)
  {
    ArrayList<String> args = new ArrayList<String>();

    try {
      MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
      ObjectName name = new ObjectName("java.lang:type=Runtime");

      String []jvmArgs
        = (String []) mbeanServer.getAttribute(name, "InputArguments");

      if (jvmArgs != null) {
        for (int i = 0; i < jvmArgs.length; i++) {
          String arg = jvmArgs[i];

          if (args.contains(arg))
            continue;

          if (arg.startsWith("-Djava.class.path=")) {
            // IBM JDK
          }
          else if (arg.startsWith("-D")) {
            int eqlSignIdx = arg.indexOf('=');
            if (eqlSignIdx == -1) {
              args.add("-J" + arg);
            } else {
              String key = arg.substring(2, eqlSignIdx);
              String value = System.getProperty(key);

              if (value == null)
                value = "";

              args.add("-J-D" + key + "=" + value);
            }
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    for (int i = 0; i < argv.length; i++)
      args.add(argv[i]);

    argv = new String[args.size()];

    args.toArray(argv);

    return argv;
  }

  private static L10N L()
  {
    if (_L == null)
      _L = new L10N(WatchdogArgs.class);

    return _L;
  }

  //
  // Utility static methods
  //

  static Path calculateResinHome()
  {
    String resinHome = System.getProperty("resin.home");

    if (resinHome != null) {
      return Vfs.lookup(resinHome);
    }

    // find the resin.jar as described by the classpath
    // this may differ from the value given by getURL() because of
    // symbolic links
    String classPath = System.getProperty("java.class.path");

    if (classPath.indexOf("resin.jar") >= 0) {
      int q = classPath.indexOf("resin.jar") + "resin.jar".length();
      int p = classPath.lastIndexOf(File.pathSeparatorChar, q - 1);

      String resinJar;

      if (p >= 0)
        resinJar = classPath.substring(p + 1, q);
      else
        resinJar = classPath.substring(0, q);

      return Vfs.lookup(resinJar).lookup("../..");
    }

    ClassLoader loader = ClassLoader.getSystemClassLoader();

    URL url = loader.getResource("com/caucho/boot/ResinBoot.class");

    String path = url.toString();

    if (! path.startsWith("jar:"))
      throw new RuntimeException(L().l("Resin/{0}: can't find jar for ResinBoot in {1}",
                                 VersionFactory.getVersion(), path));

    int p = path.indexOf(':');
    int q = path.indexOf('!');

    path = path.substring(p + 1, q);

    Path pwd = Vfs.lookup(path).getParent().getParent();

    return pwd;
  }

  static Path calculateResinRoot(Path resinHome)
  {
    String resinRoot = System.getProperty("resin.root");

    if (resinRoot != null)
      return Vfs.lookup(resinRoot);

    resinRoot = System.getProperty("server.root");

    if (resinRoot != null)
      return Vfs.lookup(resinRoot);

    return resinHome;
  }

  static String calculateClassPath(Path resinHome)
    throws IOException
  {
    ArrayList<String> classPath = new ArrayList<String>();

    return calculateClassPath(classPath, resinHome);
  }

  static String calculateClassPath(ArrayList<String> classPath,
                                   Path resinHome)
    throws IOException
  {
    String oldClassPath = System.getProperty("java.class.path");
    if (oldClassPath != null) {
      for (String item : oldClassPath.split("[" + File.pathSeparatorChar + "]")) {
        addClassPath(classPath, item);
      }
    }

    oldClassPath = System.getenv("CLASSPATH");
    if (oldClassPath != null) {
      for (String item : oldClassPath.split("[" + File.pathSeparatorChar + "]")) {
        addClassPath(classPath, item);
      }
    }

    Path javaHome = Vfs.lookup(System.getProperty("java.home"));

    if (javaHome.lookup("lib/tools.jar").canRead())
      addClassPath(classPath, javaHome.lookup("lib/tools.jar").getNativePath());
    else if (javaHome.getTail().startsWith("jre")) {
      String tail = javaHome.getTail();
      tail = "jdk" + tail.substring(3);
      Path jdkHome = javaHome.getParent().lookup(tail);

      if (jdkHome.lookup("lib/tools.jar").canRead())
        addClassPath(classPath, jdkHome.lookup("lib/tools.jar").getNativePath());
    }

    if (javaHome.lookup("../lib/tools.jar").canRead())
      addClassPath(classPath, javaHome.lookup("../lib/tools.jar").getNativePath());

    Path resinLib = resinHome.lookup("lib");

    if (resinLib.lookup("pro.jar").canRead())
      addClassPath(classPath, resinLib.lookup("pro.jar").getNativePath());
    addClassPath(classPath, resinLib.lookup("resin.jar").getNativePath());
    //    addClassPath(classPath, resinLib.lookup("jaxrpc-15.jar").getNativePath());

    String []list = resinLib.list();

    for (int i = 0; i < list.length; i++) {
      if (! list[i].endsWith(".jar"))
        continue;

      Path item = resinLib.lookup(list[i]);

      String pathName = item.getNativePath();

      if (! classPath.contains(pathName))
        addClassPath(classPath, pathName);
    }

    String cp = "";

    for (int i = 0; i < classPath.size(); i++) {
      if (! "".equals(cp))
        cp += File.pathSeparatorChar;

      cp += classPath.get(i);
    }

    return cp;
  }

  private static void addClassPath(ArrayList<String> cp, String item)
  {
    if (! cp.contains(item))
      cp.add(item);
  }

  public class ResinBootELContext
    extends ResinELContext
  {
    private boolean _isLicenseCheck;
    private boolean _isResinProfessional;

    @Override
    public Path getResinHome()
    {
      return WatchdogArgs.this.getResinHome();
    }

    @Override
    public Path getRootDirectory()
    {
      return WatchdogArgs.this.getRootDirectory();
    }

    @Override
    public Path getLogDirectory()
    {
      return WatchdogArgs.this.getLogDirectory();
    }

    @Override
    public Path getResinConf()
    {
      return WatchdogArgs.this.getResinConf();
    }

    @Override
    public String getServerId()
    {
      return WatchdogArgs.this.getServerId();
    }

    @Override
    public boolean isResinProfessional()
    {
      return isProfessional();
    }

    public boolean isProfessional()
    {
      loadLicenses();

      return _isResinProfessional;
    }

    private void loadLicenses()
    {
      if (_isLicenseCheck)
        return;

      _isLicenseCheck = true;

      LicenseCheck license;

      try {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        Class<?> cl = Class.forName("com.caucho.license.LicenseCheckImpl",
            false, loader);

        license = (LicenseCheck) cl.newInstance();

        license.requireProfessional(1);

        Vfs.initJNI();

        _isResinProfessional = true;

        // license.doLogging(1);
      } catch (Exception e) {
        log.log(Level.FINER, e.toString(), e);
      }
    }
  }

  enum StartMode {
    CONSOLE,
    DEPLOY,
    DEPLOY_CONFIG,
    DEPLOY_COPY,
    DEPLOY_LIST,
    DEPLOY_RESTART,
    DEPLOY_START,
    DEPLOY_STOP,
    DISABLE,
    DISABLE_SOFT,
    ENABLE,
    GUI,
    HEAP_DUMP,
    JMX_CALL,
    JMX_DUMP,
    JMX_LIST,
    JMX_SET,
    JSPC,
    KILL,
    LIST_RESTARTS,
    LOG_LEVEL,
    PDF_REPORT,
    PROFILE,
    RESTART,
    THREAD_DUMP,
    SHUTDOWN,
    START,
    START_WITH_FOREGROUND,
    STATUS,
    STOP,
    UNDEPLOY,
    USER_ADD,
    USER_LIST,
    USER_REMOVE,
    WATCHDOG,
  };
}
