package com.fgogotran.chapter.storage;
import android.os.ParcelFileDescriptor;

// Fixed JP test pair only. No arbitrary path, command, deletion or writable-descriptor API.
interface IChapterInstallService {
    String environment() = 0;
    String inspect(String record) = 1;
    void stage(String record, in ParcelFileDescriptor bundle, in ParcelFileDescriptor manifest) = 2;
    void commit(String record) = 3;
    void restore(String record, in ParcelFileDescriptor bundle, in ParcelFileDescriptor manifest) = 4;
    void destroy() = 16777114;
}
