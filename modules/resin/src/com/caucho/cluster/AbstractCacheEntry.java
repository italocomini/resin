/*
 * Copyright (c) 1998-2009 Caucho Technology -- all rights reserved
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

package com.caucho.cluster;

import com.caucho.cluster.ExtCacheEntry;
import com.caucho.server.cluster.ClusterTriad;
import com.caucho.server.distcache.CacheEntryKey;
import com.caucho.server.distcache.HashKey;
import com.caucho.server.distcache.CacheEntryValue;
import com.caucho.server.distcache.CacheConfig;
import com.caucho.util.Hex;

import javax.cache.CacheLoader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An entry in the cache map
 */
abstract public class AbstractCacheEntry
  implements CacheEntryKey, ExtCacheEntry {
  private final HashKey _keyHash;

  private final ClusterTriad.Owner _owner;

  //private CacheConfig _cacheConfig;

  private Object _key;

  private final AtomicBoolean _isReadUpdate
    = new AtomicBoolean();
  
  private final AtomicReference<CacheEntryValue> _entryRef
    = new AtomicReference<CacheEntryValue>();

  private int _hits;

  public AbstractCacheEntry(Object key,
		       HashKey keyHash,
		       ClusterTriad.Owner owner)
  {
    _key = key;
    _keyHash = keyHash;
    _owner = owner;
  }

//   public AbstractCacheEntry(Object key,
//		       HashKey keyHash,
//		       ClusterTriad.Owner owner,
//                       CacheConfig config)
//  {
//    _key = key;
//    _keyHash = keyHash;
//    _owner = owner;
//    _cacheConfig = config;
//  }

   /**
   * Returns the key for this entry in the Cache.
   */
  public final Object getKey()
  {
    return _key;
  }

  /**
   * Returns the value of the cache entry.
   */
  public Object getValue()
  {
    return getEntryValue().getValue();
  }

  /**
   * Returns true if the value is null.
   */
  public boolean isValueNull()
  {
    return getEntryValue().isValueNull();
  }

  /**
   * Returns the keyHash
   */
  public final HashKey getKeyHash()
  {
    return _keyHash;
  }

  /**
   * Returns the owner
   */
  public final ClusterTriad.Owner getOwner()
  {
    return _owner;
  }

  /**
   * Returns the value section of the entry.
   */
  public final CacheEntryValue getEntryValue()
  {
    return _entryRef.get();
  }

  /**
   * Peeks the current value without checking the backing store.
   */
  public Object peek()
  {
    return getEntryValue().getValue();
  }

  /**
   * Returns the object, checking the backing store if necessary.
   */
  public Object get(CacheConfig config)
  {
    CacheLoader cacheLoader = config.getCacheLoader();
    return (cacheLoader == null) ? null : cacheLoader.load(getKey());
  }

  /**
   * Fills the value with a stream
   */
  abstract public boolean getStream(OutputStream os, CacheConfig config)
    throws IOException;


  /**
   * Returns the current value.
   */
  public CacheEntryValue getEntryValue(CacheConfig config)
  {
    return getEntryValue();
  }

  /**
   * Sets the value by an input stream
   */
  abstract public Object put(Object value, CacheConfig config);

  /**
   * Sets the value by an input stream
   */
  abstract public ExtCacheEntry put(InputStream is,
			   CacheConfig config,          
			   long idleTimeout)
    throws IOException;

  /**
   * Remove the value
   */
  abstract public boolean remove(CacheConfig config);

  /**
   * Conditionally starts an update of a cache item, allowing only a
   * single thread to update the data.
   *
   * @return true if the thread is allowed to update
   */
  public final boolean startReadUpdate()
  {
    return _isReadUpdate.compareAndSet(false, true);
  }

  /**
   * Completes an update of a cache item.
   */
  public final void finishReadUpdate()
  {
    _isReadUpdate.set(false);
  }

  /**
   * Sets the current value.
   */
  public final boolean compareAndSet(CacheEntryValue oldEntryValue,
				     CacheEntryValue entryValue)
  {
    return _entryRef.compareAndSet(oldEntryValue, entryValue);
  }

   public HashKey getValueHash()
  {
    return getEntryValue().getValueHashKey();
  }

   public byte []getValueHashArray()
  {
    return getEntryValue().getValueHash();
  }

  public long getIdleTimeout()
  {
    return getEntryValue().getIdleTimeout();
  }

   public long getLeaseTimeout()
  {
    return getEntryValue().getLeaseTimeout();
  }

   public int getLeaseOwner()
  {
    return getEntryValue().getLeaseOwner();
  }

    public long getCost()
  {
    return 0;
  }

  //TODO(fred): implement as time of first put for key.
  public long getCreationTime()
  {
    return getEntryValue().getCreationTime();
  }

  public long getExpirationTime()
  {
    return getEntryValue().getExpirationTime();
  }

  public int getHits()
  {
    return getEntryValue().getHits();
  }

   public long getLastAccessTime()
   {
     return getEntryValue().getLastAccessTime();
   }

   public long getLastUpdateTime()
   {
     return getEntryValue().getLastUpdateTime();
   }

   public long getVersion()
   {
     return getEntryValue().getVersion();
   }

   public boolean isValid()
   {
     return getEntryValue().isValid();
   }


   public Object setValue(Object value)
   {
     return getEntryValue().setValue(value);
   }

  public String toString()
  {
    return (getClass().getSimpleName()
	    + "[key=" + _key
	    + ",keyHash=" + Hex.toHex(_keyHash.getHash(), 0, 4)
	    + ",owner=" + _owner
	    + "]");
  }
}