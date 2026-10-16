package com.fgogotran.chapter.storage;

import android.os.ParcelFileDescriptor;

// No arbitrary paths, commands, file writes, deletion or installation operation.
interface IChapterFileService {
    int getServiceUid() = 0;
    ParcelFileDescriptor openOriginal(String filename) = 1;
    String fingerprint(String filename) = 2;
    void destroy() = 16777114;
}
