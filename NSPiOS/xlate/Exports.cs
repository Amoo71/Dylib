using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

/// C entry point for NSPiOS: the same arguments the dmsh_xlate executable takes (without the program name).
static unsafe class NSPiOSXlateExports
{
    static bool s_console;

    /// On iOS, .NET sends Console output to NSLog (with a timestamp prefix). The packer reads the translator's plain
    /// stdout/stderr, so write straight to file descriptors 1 and 2, which NSPiOS redirects for each call.
    static void PlainConsole()
    {
        if (s_console) return;
        s_console = true;
        var utf8 = new UTF8Encoding(false);
        Console.SetOut(new StreamWriter(new FdStream(1), utf8) { AutoFlush = true });
        Console.SetError(new StreamWriter(new FdStream(2), utf8) { AutoFlush = true });
    }

    [UnmanagedCallersOnly(EntryPoint = "nspios_dmsh_xlate_main")]
    static int Run(int argc, byte** argv)
    {
        PlainConsole();
        var args = new string[argc];
        for (int i = 0; i < argc; i++) args[i] = Marshal.PtrToStringUTF8((IntPtr)argv[i]) ?? "";
        try
        {
            int rc = SwshXlate.Main(args);
            Console.Out.Flush(); Console.Error.Flush();
            return rc;
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("[RED] dmsh_xlate: " + e.GetType().Name + ": " + e.Message);
            Console.Error.Flush();
            return 70;
        }
    }
}

/// Write-only stream on a raw file descriptor (write(2)); no position of its own, so the descriptor may be redirected
/// between calls.
sealed unsafe class FdStream : Stream
{
    readonly int _fd;
    public FdStream(int fd) { _fd = fd; }

    [DllImport("/usr/lib/libSystem.B.dylib", EntryPoint = "write")]
    static extern nint SysWrite(int fd, byte* buffer, nint count);

    public override void Write(byte[] buffer, int offset, int count) => Write(new ReadOnlySpan<byte>(buffer, offset, count));

    public override void Write(ReadOnlySpan<byte> data)
    {
        fixed (byte* start = data)
        {
            byte* p = start;
            nint left = data.Length;
            while (left > 0)
            {
                nint n = SysWrite(_fd, p, left);
                if (n <= 0) return;
                p += n;
                left -= n;
            }
        }
    }

    public override bool CanRead => false;
    public override bool CanSeek => false;
    public override bool CanWrite => true;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override void Flush() { }
    public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
}
