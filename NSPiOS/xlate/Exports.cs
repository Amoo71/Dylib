using System;
using System.Runtime.InteropServices;

/// C entry point for NSPiOS: the same arguments the dmsh_xlate executable takes (without the program name).
static unsafe class NSPiOSXlateExports
{
    [UnmanagedCallersOnly(EntryPoint = "nspios_dmsh_xlate_main")]
    static int Run(int argc, byte** argv)
    {
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
