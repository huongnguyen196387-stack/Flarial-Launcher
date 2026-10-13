package com.launcher.core.data.shizuku;

// IPC của UserService Shizuku. Dữ liệu lớn luôn truyền theo chunk nhỏ.
interface IPrivilegedFileService {
    void destroy() = 16777114;
    byte[] read(String path) = 1;
    void writeAtomic(String path, in byte[] data) = 2;
    boolean delete(String path) = 3;
    boolean exists(String path) = 4;
    String sha256(String path) = 5;
    boolean isProcessRunning(String pkg) = 6;
    String[] shell(in String[] argv) = 7;
    boolean tryLock(String name, long timeoutMs) = 8;
    void unlock(String name) = 9;

    // Các transaction này không bao giờ nhận cả file mod; writeChunk bị giới hạn 64 KiB.
    int beginWrite(String path) = 10;
    void writeChunk(int writeId, in byte[] chunk) = 11;
    void commitWrite(int writeId) = 12;
    void abortWrite(int writeId) = 13;
}
