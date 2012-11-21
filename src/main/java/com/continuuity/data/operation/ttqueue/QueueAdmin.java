package com.continuuity.data.operation.ttqueue;

import com.continuuity.api.data.OperationBase;
import org.apache.hadoop.hbase.util.Bytes;
import org.codehaus.jettison.json.JSONException;

import com.continuuity.api.data.ReadOperation;
import com.continuuity.data.operation.ttqueue.internal.EntryPointer;
import com.continuuity.data.operation.ttqueue.internal.ExecutionMode;
import com.continuuity.data.operation.ttqueue.internal.GroupState;
import com.continuuity.hbase.ttqueue.HBQQueueMeta;
import com.google.common.base.Objects;

import java.util.Arrays;

public class QueueAdmin {

  /**
   * Generates and returns a unique group id for the speicified queue.
   */
  public static class GetGroupID implements ReadOperation {

    /** Unique id for the operation */
    private final long id = OperationBase.getId();

    private final byte [] queueName;

    public GetGroupID(final byte [] queueName) {
      this.queueName = queueName;
    }

    public byte [] getQueueName() {
      return this.queueName;
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(this)
          .add("queuename", Bytes.toString(this.queueName))
          .toString();
    }

    @Override
    public long getId() {
      return id;
    }
  }

  public static class GetQueueMeta implements ReadOperation {

    /** Unique id for the operation */
    private final long id;
    private final byte [] queueName;

    public GetQueueMeta(byte [] queueName) {
      this(OperationBase.getId(), queueName);
    }

    public GetQueueMeta(final long id,
                        byte [] queueName) {
      this.id = id;
      this.queueName = queueName;
    }

    public byte [] getQueueName() {
      return this.queueName;
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(this)
          .add("queuename", Bytes.toString(this.queueName))
          .toString();
    }

    @Override
    public long getId() {
      return id;
    }
  }

  public static class QueueMeta {
    long globalHeadPointer;
    long currentWritePointer;
    GroupState [] groups;
    String jsonString = null;

    public long getGlobalHeadPointer() {
      return this.globalHeadPointer;
    }

    public long getCurrentWritePointer() {
      return this.currentWritePointer;
    }

    public GroupState [] getGroups() {
      return this.groups;
    }

    public String getJSONString() {
      return this.jsonString;
    }

    public QueueMeta() { }

    public QueueMeta(long globalHeadPointer, long currentWritePointer,
                     GroupState[] groups) {
      this.globalHeadPointer = globalHeadPointer;
      this.currentWritePointer = currentWritePointer;
      this.groups = groups;
    }

    public QueueMeta(HBQQueueMeta queueMeta) {
      try {
        this.jsonString = queueMeta.toJSON();
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }

    public boolean isHBQMeta() {
      return this.jsonString != null;
    }

    @Override
    public String toString() {
      if (this.jsonString != null) {
        return this.jsonString;
      }
      return Objects.toStringHelper(this)
          .add("globalHeadPointer", this.globalHeadPointer)
          .add("currentWritePointer", this.currentWritePointer)
          .add("groups", this.groups)
          .toString();
    }

    @Override
    public boolean equals(Object object) {
      if (object == null || !(object instanceof QueueMeta))
        return false;
      QueueMeta other = (QueueMeta)object;
      if (this.jsonString != null) {
        return this.jsonString.equals(other.jsonString);
      }
      return
          this.currentWritePointer == other.currentWritePointer &&
          this.globalHeadPointer == other.globalHeadPointer &&
          Arrays.equals(this.groups, other.groups);
    }
  }
}
