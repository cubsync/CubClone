/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: D:\Dev\Android\Sdk\build-tools\35.0.0\aidl.exe -I D:\Dev\Workspaces\WorkBuddy\SelfDeviceCheck\moto-port\apk-src\libs\t -o D:\Dev\Workspaces\WorkBuddy\SelfDeviceCheck\moto-port\apk-src\libs\tgen D:\Dev\Workspaces\WorkBuddy\SelfDeviceCheck\moto-port\apk-src\libs\t\test\ITest.aidl
 */
package test;
public interface ITest extends android.os.IInterface
{
  /** Default implementation for ITest. */
  public static class Default implements test.ITest
  {
    @Override public void foo() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements test.ITest
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an test.ITest interface,
     * generating a proxy if needed.
     */
    public static test.ITest asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof test.ITest))) {
        return ((test.ITest)iin);
      }
      return new test.ITest.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_foo:
        {
          this.foo();
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements test.ITest
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public void foo() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_foo, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_foo = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "test.ITest";
  public void foo() throws android.os.RemoteException;
}
