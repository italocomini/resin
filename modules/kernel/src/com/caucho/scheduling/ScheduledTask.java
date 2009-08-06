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
package com.caucho.scheduling;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.caucho.config.types.Trigger;
import com.caucho.util.Alarm;
import com.caucho.util.AlarmListener;
import com.caucho.util.ThreadPool;

/**
 * Scheduled task.
 * 
 * @author Reza Rahman
 */
public class ScheduledTask implements AlarmListener {
  private static ClassLoader _loader = Thread.currentThread()
      .getContextClassLoader();
  private static AtomicLong _currentTaskId = new AtomicLong();

  private long _taskId;
  @SuppressWarnings("unchecked")
  private Class _targetBean;
  private Method _targetMethod;
  private Runnable _task;
  private CronExpression _cronExpression;
  private Trigger _trigger;
  private Alarm _alarm;
  private long _start;
  private long _end;
  private Serializable _data;
  private AtomicBoolean _cancelled = new AtomicBoolean();

  /**
   * Constructs a new scheduled task.
   * 
   * @param targetBean
   *          The target bean to be invoked by the task.
   * @param targetMethod
   *          The target method to be invoked by the task.
   * @param task
   *          The task to be invoked.
   * @param cronExpression
   *          The cron expression used to create the schedule, if any.
   * @param trigger
   *          The trigger for the schedule.
   * @param start
   *          The start date, in milliseconds for the scheduled task. -1 used to
   *          indicate no start date.
   * @param end
   *          The end date, in milliseconds for the scheduled task. -1 used to
   *          indicate no end date.
   * @param data
   *          The data to be passed to the invocation target.
   */
  @SuppressWarnings("unchecked")
  public ScheduledTask(final Class targetBean, final Method targetMethod,
      final Runnable task, final CronExpression cronExpression,
      final Trigger trigger, final long start, final long end,
      final Serializable data)
  {
    _taskId = _currentTaskId.incrementAndGet();
    _targetBean = targetBean;
    _targetMethod = targetMethod;
    _task = task;
    _cronExpression = cronExpression;
    _trigger = trigger;
    _cronExpression = cronExpression;
    _start = start;
    _end = end;
    _data = data;

    long now = Alarm.getCurrentTime();
    long nextTime = _trigger.nextTime(now + 500);
    _alarm = new Alarm(this, nextTime - now); // TODO Try a weak alarm instead.
  }

  /**
   * Gets task ID.
   * 
   * @return Task ID.
   */
  public long getTaskId()
  {
    return _taskId;
  }

  /**
   * Gets target bean.
   * 
   * @return Target bean.
   */
  @SuppressWarnings("unchecked")
  public Class getTargetBean()
  {
    return _targetBean;
  }

  /**
   * Gets the target method to be invoked by the scheduled task.
   * 
   * @return Target method (may be null).
   */
  public Method getTargetMethod()
  {
    return _targetMethod;
  }

  /**
   * Gets the cron expression used to create the schedule.
   * 
   * @return Cron expression used to create the schedule, if one was used.
   */
  public CronExpression getCronExpression()
  {
    return _cronExpression;
  }

  /**
   * Gets the start date for the schedule.
   * 
   * @return Start date for schedule.
   */
  public long getStart()
  {
    return _start;
  }

  /**
   * Gets the end date for the schedule.
   * 
   * @return End date for schedule.
   */
  public long getEnd()
  {
    return _end;
  }

  /**
   * Gets the data to be passed to the invocation target.
   * 
   * @return Data to be passed to the invocation target.
   */
  public Serializable getData()
  {
    return _data;
  }

  /**
   * Get the next time, in milliseconds, when the alarm will be triggered for
   * the scheduled task.
   * 
   * @return The next time, in milliseconds, when the alarm will be triggered
   *         for the scheduled task.
   */
  public long getNextAlarmTime()
  {
    return _trigger.nextTime(Alarm.getExactTime() + 500);
  }

  /**
   * Cancels the scheduled task. Any currently running tasks will not be
   * terminated, but no more triggers will be fired for this task.
   */
  public void cancel()
  {
    // TODO This should probably be a proper lookup of the timer service
    // (perhaps via JCDI).
    Scheduler.removeScheduledTask(this);
    _cancelled.set(true);
    _alarm.dequeue();
  }

  /**
   * Gets the current status of this task.
   * 
   * @return Status of the scheduled task.
   */
  public ScheduledTaskStatus getStatus()
  {
    if (_cancelled.get()) {
      return ScheduledTaskStatus.CANCELLED;
    }

    long now = Alarm.getExactTime();
    long nextTime = _trigger.nextTime(now + 500);

    if (now > nextTime) {
      return ScheduledTaskStatus.EXPIRED;
    }

    return ScheduledTaskStatus.ACTIVE;
  }

  /**
   * Handles alarm.
   * 
   * @param alarm
   *          Alarm to handle.
   */
  @Override
  public void handleAlarm(final Alarm alarm)
  {
    Thread thread = Thread.currentThread();
    ClassLoader oldLoader = thread.getContextClassLoader();

    try {
      thread.setContextClassLoader(_loader);

      ThreadPool.getCurrent().schedule(_task);

      long now = Alarm.getExactTime();
      long nextTime = _trigger.nextTime(now + 500);

      alarm.queue(nextTime - now);
    } finally {
      thread.setContextClassLoader(oldLoader);
    }
  }

  /**
   * Gets the hash code for this object.
   * 
   * @return Hash code for this object.
   */
  @Override
  public int hashCode()
  {
    final int prime = 31;
    int result = 1;
    result = prime * result + (int) (_taskId ^ (_taskId >>> 32));
    return result;
  }

  /**
   * Checks for equality with another object.
   * 
   * @param object
   *          The object to compare with.
   * @return True if the objects are not equal, false otherwise.
   */
  @Override
  public boolean equals(final Object object)
  {
    if (this == object)
      return true;
    if (object == null)
      return false;
    if (getClass() != object.getClass())
      return false;
    ScheduledTask other = (ScheduledTask) object;
    if (_taskId != other.getTaskId())
      return false;
    return true;
  }
}