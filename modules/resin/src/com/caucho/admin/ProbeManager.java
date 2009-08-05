/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
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

package com.caucho.admin;

import java.util.concurrent.ConcurrentHashMap;

public class ProbeManager {
  private static ProbeManager _manager = new ProbeManager();

  private final ConcurrentHashMap<String,Probe> _probeMap
    = new ConcurrentHashMap<String,Probe>();

  protected ProbeManager()
  {
  }

  protected void setManager(ProbeManager manager)
  {
    _manager = manager;
  }

  public static ProbeManager getCurrent()
  {
    return _manager;
  }

  public static AverageTimeProbe createAverageTimeProbe(String name)
  {
    return _manager.createAverageTimeProbeImpl(name);
  }

  private AverageTimeProbe createAverageTimeProbeImpl(String name)
  {
    Probe probe = _probeMap.get(name);

    if (probe == null) {
      probe = createProbe(new AverageTimeProbe(name));
    }
    
    return (AverageTimeProbe) probe;
  }

  public static SampleCountProbe createSampleCountProbe(String name)
  {
    return _manager.createSampleCountProbeImpl(name);
  }

  private SampleCountProbe createSampleCountProbeImpl(String name)
  {
    Probe probe = _probeMap.get(name);

    if (probe == null) {
      probe = createProbe(new SampleCountProbe(name));
    }
    
    return (SampleCountProbe) probe;
  }

  protected Probe createProbe(Probe newProbe)
  {
    Probe probe = _probeMap.putIfAbsent(newProbe.getName(), newProbe);

    if (probe != null) {
      return probe;
    }
    else {
      registerProbe(newProbe);

      return newProbe;
    }
  }

  protected void registerProbe(Probe probe)
  {
  }
}
