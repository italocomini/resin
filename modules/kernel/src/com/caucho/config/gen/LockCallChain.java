/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
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
 * @author Reza Rahman
 */
package com.caucho.config.gen;

import static javax.ejb.ConcurrencyManagementType.CONTAINER;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import javax.ejb.AccessTimeout;
import javax.ejb.ConcurrencyManagement;
import javax.ejb.Lock;
import javax.ejb.LockType;

import com.caucho.config.Configurable;
import com.caucho.config.types.Period;
import com.caucho.ejb.locking.ConcurrencyNotAllowedLock;
import com.caucho.java.JavaWriter;
import com.caucho.util.L10N;

/**
 * Represents EJB lock type specification interception. The specification gears
 * it towards EJB singletons, but it can be used for other bean types.
 */
public class LockCallChain extends AbstractCallChain {
  @SuppressWarnings("unused")
  private static final L10N L = new L10N(LockCallChain.class);

  private EjbCallChain _next;

  private boolean _isContainerManaged;
  private LockType _lockType;
  private boolean _concurrencyNotAllowed;
  private long _lockTimeout;
  private TimeUnit _lockTimeoutUnit;

  public LockCallChain(BusinessMethodGenerator businessMethod, EjbCallChain next)
  {
    super(next);

    _next = next;

    _isContainerManaged = true;
    _lockType = null;
    _concurrencyNotAllowed = false;

    _lockTimeout = 10000;
    _lockTimeoutUnit = TimeUnit.MILLISECONDS;
  }

  /**
   * Sets the lock timeout.
   * 
   * @param timeout
   *          The timeout period.
   */
  @Configurable
  public void setTimeout(Period timeout)
  {
    _lockTimeout = timeout.getPeriod();
  }

  /**
   * Returns true if the business method has a lock annotation.
   */
  @Override
  public boolean isEnhanced()
  {
    return (_isContainerManaged && ((_lockType != null) || _concurrencyNotAllowed));
  }

  /**
   * Introspects the method for locking attributes.
   */
  @Override
  public void introspect(ApiMethod apiMethod, ApiMethod implementationMethod)
  {
    ApiClass apiClass = apiMethod.getDeclaringClass();

    ConcurrencyManagement concurrencyManagementAnnotation = apiClass
        .getAnnotation(ConcurrencyManagement.class);

    if ((concurrencyManagementAnnotation != null)
        && (concurrencyManagementAnnotation.value() != CONTAINER)) {
      _isContainerManaged = false;
      return;
    }

    ApiClass implementationClass = null;

    if (implementationMethod != null) {
      implementationClass = implementationMethod.getDeclaringClass();
    }

    Lock lockAttribute = getAnnotation(Lock.class, apiMethod, apiClass,
        implementationMethod, implementationClass);

    if (lockAttribute != null) {
      _lockType = lockAttribute.value();
    }

    AccessTimeout accessTimeoutAttribute = getAnnotation(AccessTimeout.class,
        apiMethod, apiClass, implementationMethod, implementationClass);

    if (accessTimeoutAttribute != null) {
      _lockTimeout = accessTimeoutAttribute.timeout();
      _lockTimeoutUnit = accessTimeoutAttribute.unit();
    }

    ConcurrencyNotAllowedLock concurrencyNotAllowedAttribute = getAnnotation(
        ConcurrencyNotAllowedLock.class, apiMethod, apiClass,
        implementationMethod, implementationClass);

    if (concurrencyNotAllowedAttribute != null) {
      _concurrencyNotAllowed = true;
    }
  }

  // TODO Should this be moved into the abstract super class?
  @SuppressWarnings("unchecked")
  private <T extends Annotation> T getAnnotation(Class<T> annotationType,
      ApiMethod apiMethod, ApiClass apiClass, ApiMethod implementationMethod,
      ApiClass implementationClass)
  {
    Annotation annotation;

    annotation = apiMethod.getAnnotation(annotationType);

    if (annotation == null) {
      annotation = apiClass.getAnnotation(annotationType);
    }

    if ((annotation == null) && (implementationMethod != null)) {
      annotation = implementationMethod.getAnnotation(annotationType);
    }

    if ((annotation == null) && (implementationClass != null)) {
      annotation = implementationClass.getAnnotation(annotationType);
    }

    return (T) annotation;
  }

  /**
   * Generates the class prologue.
   */
  @SuppressWarnings("unchecked")
  @Override
  public void generatePrologue(JavaWriter out, HashMap map) throws IOException
  {
    if ((_isContainerManaged && ((_lockType != null) || _concurrencyNotAllowed))
        && (map.get("caucho.ejb.lock") == null)) {
      // TODO Does this need be registered somewhere?
      map.put("caucho.ejb.lock", "done");

      out.println();
      out
          .println("private transient final java.util.concurrent.locks.ReadWriteLock _readWriteLock");
      out
          .println("  = new java.util.concurrent.locks.ReentrantReadWriteLock();");
      out.println();
    }

    _next.generatePrologue(out, map);
  }

  /**
   * Generates the method interception code.
   */
  @Override
  public void generateCall(JavaWriter out) throws IOException
  {
    // TODO Is this too much code to be in-lined?
    if (_isContainerManaged && ((_lockType != null) || _concurrencyNotAllowed)) {
      if (_concurrencyNotAllowed) {
        out.println();
        out.println("if (_readWriteLock.writeLock().tryLock()) {");
        out.pushDepth();
        out.println("try {");
        out.pushDepth();
        out.println();
      } else {
        switch (_lockType) {
        case READ:
          out.println();
          out.println("try {");
          out.pushDepth();
          out.println("if (_readWriteLock.readLock().tryLock("
              + _lockTimeoutUnit.toMillis(_lockTimeout)
              + ", java.util.concurrent.TimeUnit.MILLISECONDS)) {");
          out.pushDepth();
          out.println("try {");
          out.pushDepth();
          out.println();
          break;

        case WRITE:
          out.println();
          out.println("try {");
          out.pushDepth();
          out.println("if (_readWriteLock.writeLock().tryLock("
              + _lockTimeoutUnit.toMillis(_lockTimeout)
              + ", java.util.concurrent.TimeUnit.MILLISECONDS)) {");
          out.pushDepth();
          out.println("try {");
          out.pushDepth();
          out.println();
          break;
        }
      }
    }

    generateNext(out);

    if (_isContainerManaged && ((_lockType != null) || _concurrencyNotAllowed)) {
      if (_concurrencyNotAllowed) {
        out.popDepth();
        out.println("} finally {");
        out.pushDepth();
        out.println("_readWriteLock.writeLock().unlock();");
        out.popDepth();
        out.println("}");
        out.popDepth();
        out.println("} else {");
        out.pushDepth();
        out
            .println("throw new javax.ejb.ConcurrentAccessException(\"Concurrent access not allowed\");");
        out.popDepth();
        out.println("}");
        out.println();
      } else {
        switch (_lockType) {
        case READ:
          out.popDepth();
          out.println("} finally {");
          out.pushDepth();
          out.println("_readWriteLock.readLock().unlock();");
          out.popDepth();
          out.println("}");
          out.popDepth();
          out.println("} else {");
          out.pushDepth();
          out
              .println("throw new javax.ejb.ConcurrentAccessTimeoutException(\"Timed out acquiring read lock.\");");
          out.popDepth();
          out.println("}");
          out.popDepth();
          out.println("} catch (InterruptedException interruptedException) {");
          out.pushDepth();
          out
              .println("throw new javax.ejb.ConcurrentAccessTimeoutException(\"Thread interruption acquiring read lock: \" + interruptedException.getMessage());");
          out.popDepth();
          out.println("}");
          out.println();
          break;
        case WRITE:
          out.popDepth();
          out.println("} finally {");
          out.pushDepth();
          out.println("_readWriteLock.writeLock().unlock();");
          out.popDepth();
          out.println("}");
          out.popDepth();
          out.println("} else {");
          out.pushDepth();
          out
              .println("throw new javax.ejb.ConcurrentAccessTimeoutException(\"Timed out acquiring write lock.\");");
          out.popDepth();
          out.println("}");
          out.popDepth();
          out.println("} catch (InterruptedException interruptedException) {");
          out.pushDepth();
          out
              .println("throw new javax.ejb.ConcurrentAccessTimeoutException(\"Thread interruption acquiring write lock: \" + interruptedException.getMessage());");
          out.popDepth();
          out.println("}");
          out.println();
          break;
        }
      }
    }
  }

  protected void generateNext(JavaWriter out) throws IOException
  {
    _next.generateCall(out);
  }
}