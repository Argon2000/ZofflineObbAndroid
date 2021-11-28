package com.zoffline.zofflineobb;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionMethod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static android.os.Build.VERSION.SDK_INT;
import static android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void patchObb(View view) throws IOException {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, 1);

        Button btn = findViewById(R.id.patch_obb);
        btn.setEnabled(false);

        AssetManager assetManager = this.getAssets();

        if(SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                addInfo("Please allow access to all files");
                Intent intent = new Intent(ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
                btn.setEnabled(true);
                return;
            }
        }

        if(!getPackageManager().canRequestPackageInstalls()) {
            addInfo("Please allow app to install APK (to access OBB folder)");
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            startActivity(intent);
            btn.setEnabled(true);
            return;
        }

        Thread t1 = new Thread(() -> {
            addInfo("Started...");

            addInfo("Locating file...");
            File sdCardRoot = Environment.getExternalStorageDirectory();
            File folder = new File(sdCardRoot, "Android/obb/com.zwift.zwiftgame");
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase().endsWith("zwiftgame.obb"));

            if(files != null && files.length > 0) {
                File obb_file = files[0];
                if(obb_file == null) {
                    addInfo("File not found.");
                    btn.setEnabled(true);
                    return;
                }

                //Rename OBB to ZIP
                addInfo("Renaming .obb to .zip");
                File zip_file = new File(obb_file.getPath().replace(".obb", ".zip"));

                if (!zip_file.exists()) {
                    try {
                        zip_file.createNewFile();
                    } catch (IOException e) {
                        addInfo(e.getMessage());
                    }
                }

                FileChannel source = null;
                FileChannel destination = null;

                try {
                    source = new FileInputStream(obb_file).getChannel();
                    destination = new FileOutputStream(zip_file).getChannel();
                    destination.transferFrom(source, 0, source.size());
                } catch (FileNotFoundException e) {
                    addInfo(e.getMessage());
                } catch (IOException e) {
                    addInfo(e.getMessage());
                } finally {
                    if (source != null) {
                        try {
                            source.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                    if (destination != null) {
                        try {
                            destination.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                }

                //Unzip
                addInfo("Unzipping, this will take a while...");
                String extract_dir = zip_file.getParent() + "/extracted/";
                unzip(zip_file.getPath(), extract_dir);

                //Delete ZIP
                addInfo("Deleting ZIP file...");
                if(zip_file.exists()) {
                    zip_file.delete();
                }

                //Replace cacert.pem file
                addInfo("Replacing cacert.pem with modified...");
                File cert_dest = new File(extract_dir + "/dataES/cacert.pem");
                if(cert_dest.exists()) {
                    cert_dest.delete();
                }
                try {
                    cert_dest.createNewFile();
                } catch (IOException e) {
                    addInfo(e.getMessage());
                }
                InputStream source_stream = null;
                FileOutputStream destination_stream = null;
                try {
                    source_stream = assetManager.open("cacert.pem");
                    destination_stream  = new FileOutputStream(cert_dest);
                    copyFile(source_stream, destination_stream);
                }
                catch (Exception e) {
                    String ex = e.getMessage();
                }
                finally {
                    if (source_stream != null) {
                        try {
                            source_stream.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                    if (destination_stream != null) {
                        try {
                            destination_stream.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                }

                //Zip folder again
                addInfo("Zipping modified folder, this will take some time...");
                zipFolder(extract_dir + "/dataES", zip_file.getPath());

                //Check file sizes
                addInfo("Checking file sizes...");
                long org_size = 0;
                long new_size = 0;
                try {
                    new_size = Files.size(Paths.get(zip_file.getPath()));
                    org_size = Files.size(Paths.get(obb_file.getPath()));
                } catch (IOException e) {
                    addInfo(e.getMessage());
                }
                long diff = org_size - new_size - 182;
                addInfo("Difference in size: " + diff);

                String dummy_path = obb_file.getParent() + "/dummy.file";
                File dummy_file = new File(dummy_path);

                if(diff > 0) {
                    addInfo("Making a dummy file with size: " + diff);
                    if(dummy_file.exists()) {
                        dummy_file.delete();
                    }
                    try {
                        dummy_file.createNewFile();
                        RandomAccessFile raf = new RandomAccessFile(dummy_file, "rw");
                        raf.setLength(diff);
                        raf.close();
                    } catch (FileNotFoundException e) {
                        addInfo(e.getMessage());
                    }  catch (IOException e) {
                        addInfo(e.getMessage());
                    }

                    //Append dummy.file to zip-archive
                    addInfo("Adding dummy-file to zip...");
                    ZipFile z_file = new ZipFile(zip_file);
                    ZipParameters p = new ZipParameters();
                    p.setDefaultFolderPath("dataES");
                    p.setCompressionMethod(CompressionMethod.STORE);
                    try {
                        z_file.addFile(dummy_file, p);
                    } catch (ZipException e) {
                        addInfo(e.getMessage());
                    }

                    //Check file sizes
                    addInfo("Checking file sizes...");
                    try {
                        new_size = Files.size(Paths.get(zip_file.getPath()));
                        org_size = Files.size(Paths.get(obb_file.getPath()));
                    } catch (IOException e) {
                        addInfo(e.getMessage());
                    }
                    diff = org_size - new_size;
                    addInfo("Difference in size: " + diff);

                    if(diff != 0) {
                        addInfo("Filesize not equal, installation might fail...");
                    }
                }

                //Rename ZIP to OBB
                addInfo("Renaming .zip to .obb");
                if (!obb_file.exists()) {
                    try {
                        obb_file.createNewFile();
                    } catch (IOException e) {
                        addInfo(e.getMessage());
                    }
                }

                source = null;
                destination = null;

                try {
                    source = new FileInputStream(zip_file).getChannel();
                    destination = new FileOutputStream(obb_file).getChannel();
                    destination.transferFrom(source, 0, source.size());
                } catch (FileNotFoundException e) {
                    addInfo(e.getMessage());
                } catch (IOException e) {
                    addInfo(e.getMessage());
                } finally {
                    if (source != null) {
                        try {
                            source.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                    if (destination != null) {
                        try {
                            destination.close();
                        } catch (IOException e) {
                            addInfo(e.getMessage());
                        }
                    }
                }

                addInfo("Removing temporary files...");
                if(dummy_file.exists()) {
                    dummy_file.delete();
                }
                if(zip_file.exists()) {
                    zip_file.delete();
                }
                File extract_folder = new File(extract_dir);
                deleteDirectory(extract_folder);

                addInfo("Done!");
            }
            else {
                addInfo("File not found.");
                btn.setEnabled(true);
            }
        });
        t1.start();
    }

    public void addInfo(String text) {
        TextView info = findViewById(R.id.textView);
        info.setText(info.getText() + "\r\n" + text);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
            out.write(buffer, 0, read);
        }
    }

    public void unzip(String _zipFile, String _targetLocation) {
        //create target location folder if not exist
        dirChecker(_targetLocation);

        try {
            FileInputStream fin = new FileInputStream(_zipFile);
            ZipInputStream zin = new ZipInputStream(fin);
            ZipEntry ze = null;
            byte[] buffer = new byte[8192];
            while ((ze = zin.getNextEntry()) != null) {

                //create dir if required while unzipping
                if (ze.isDirectory()) {
                    dirChecker(_targetLocation + ze.getName());
                } else {
                    FileOutputStream fout = new FileOutputStream(_targetLocation + ze.getName());
                    for (int c = zin.read(buffer); c != -1; c = zin.read(buffer)) {
                        fout.write(buffer, 0, c);
                    }

                    zin.closeEntry();
                    fout.close();
                }

            }
            zin.close();
        } catch (Exception e) {
            addInfo(e.getMessage());
        }
    }

    private void dirChecker(String path) {
        File dir = new File(path);
        if (!dir.isDirectory() && !dir.mkdirs())
            addInfo("Failed to create folder: " + dir.getAbsolutePath());
    }

    public boolean zipFolder(String sourcePath, String toLocation) {
        final int BUFFER = 2048;

        File sourceFile = new File(sourcePath);
        try {
            BufferedInputStream origin = null;
            FileOutputStream dest = new FileOutputStream(toLocation);
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(
                    dest));
            if (sourceFile.isDirectory()) {
                zipSubFolder(out, sourceFile, sourceFile.getParent().length()+1);
            } else {
                byte data[] = new byte[BUFFER];
                FileInputStream fi = new FileInputStream(sourcePath);
                origin = new BufferedInputStream(fi, BUFFER);
                ZipEntry entry = new ZipEntry(getLastPathComponent(sourcePath));
                entry.setTime(sourceFile.lastModified()); // to keep modification time after unzipping
                out.putNextEntry(entry);
                int count;
                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
            }
            out.close();
        } catch (Exception e) {
            addInfo(e.getMessage());
            return false;
        }
        return true;
    }

    private void zipSubFolder(ZipOutputStream out, File folder, int basePathLength) throws IOException {
        final int BUFFER = 2048;

        File[] fileList = folder.listFiles();
        BufferedInputStream origin = null;
        for (File file : fileList) {
            if (file.isDirectory()) {
                zipSubFolder(out, file, basePathLength);
            } else {
                byte data[] = new byte[BUFFER];
                String unmodifiedFilePath = file.getPath();
                String relativePath = unmodifiedFilePath
                        .substring(basePathLength);
                FileInputStream fi = new FileInputStream(unmodifiedFilePath);
                origin = new BufferedInputStream(fi, BUFFER);
                ZipEntry entry = new ZipEntry(relativePath);
                entry.setTime(file.lastModified()); // to keep modification time after unzipping
                out.putNextEntry(entry);
                int count;
                while ((count = origin.read(data, 0, BUFFER)) != -1) {
                    out.write(data, 0, count);
                }
                origin.close();
            }
        }
    }

    public String getLastPathComponent(String filePath) {
        String[] segments = filePath.split("/");
        if (segments.length == 0)
            return "";
        String lastPathComponent = segments[segments.length - 1];
        return lastPathComponent;
    }

    public boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }
}