package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the highest-risk detail in the platform backup feature.
 *
 * <p>{@link GoogleDriveService#findOrCreateFolder()} short-circuits on a
 * configured {@code google.drive.folder-id}, ignoring the folder name entirely.
 * {@code GOOGLE_DRIVE_FOLDER_ID} is set in production via {@code env_file: .env},
 * so a naive {@code findOrCreateFolder()} call from the platform path would return
 * the <em>application</em> backup folder — and since retention keeps the newest
 * seven {@code *.zip} in a folder, the two backup types would evict each other and
 * silently degrade today's "last 7 days" guarantee to roughly "last 3 days" of
 * each.
 *
 * <p>That failure is invisible until someone needs a six-day-old backup, which is
 * why it gets its own test class rather than an assertion tucked into another one.
 */
class GoogleDriveFolderResolutionTest {

  private static final String CONFIGURED_APPLICATION_FOLDER_ID = "configured-app-folder";
  private static final String DISCOVERED_PLATFORM_FOLDER_ID = "discovered-platform-folder";

  private Drive drive;
  private Drive.Files files;
  private Drive.Files.List list;

  @BeforeEach
  void setUp() throws IOException {
    drive = mock(Drive.class);
    files = mock(Drive.Files.class);
    list = mock(Drive.Files.List.class);

    when(drive.files()).thenReturn(files);
    when(files.list()).thenReturn(list);
    when(list.setQ(anyString())).thenReturn(list);
    when(list.setFields(anyString())).thenReturn(list);
    when(list.setPageSize(anyInt())).thenReturn(list);
    when(list.setSupportsAllDrives(anyBoolean())).thenReturn(list);
    when(list.setIncludeItemsFromAllDrives(anyBoolean())).thenReturn(list);
  }

  private void driveContainsFolder(final String id) throws IOException {
    File folder = new File();
    folder.setId(id);
    folder.setName(GoogleDriveService.PLATFORM_FOLDER_NAME);
    FileList result = new FileList();
    result.setFiles(List.of(folder));
    when(list.execute()).thenReturn(result);
  }

  private void driveContainsNoFolders() throws IOException {
    FileList result = new FileList();
    result.setFiles(List.of());
    when(list.execute()).thenReturn(result);
  }

  /**
   * The assertion this whole class exists for: with the application folder pinned
   * by configuration, the two resolvers must return <em>different</em> folders.
   */
  @Test
  void resolvesPlatformBackupsToDifferentFolderThanConfiguredApplicationFolder()
      throws IOException {
    driveContainsFolder(DISCOVERED_PLATFORM_FOLDER_ID);
    GoogleDriveService service =
        new GoogleDriveService(drive, CONFIGURED_APPLICATION_FOLDER_ID, "");

    String applicationFolder = service.findOrCreateFolder();
    String platformFolder = service.findOrCreatePlatformFolder();

    assertThat(applicationFolder).isEqualTo(CONFIGURED_APPLICATION_FOLDER_ID);
    assertThat(platformFolder).isEqualTo(DISCOVERED_PLATFORM_FOLDER_ID);
    assertThat(platformFolder).isNotEqualTo(applicationFolder);
  }

  /**
   * The application path must behave exactly as it does today. FR-014 and SC-004
   * make "the existing backup is unchanged" a verifiable requirement, and this is
   * where it is verified.
   */
  @Test
  void leavesTheApplicationFolderResolutionUnchangedWhenConfigured() throws IOException {
    GoogleDriveService service =
        new GoogleDriveService(drive, CONFIGURED_APPLICATION_FOLDER_ID, "");

    assertThat(service.findOrCreateFolder()).isEqualTo(CONFIGURED_APPLICATION_FOLDER_ID);
    // Short-circuits before touching Drive at all — no lookup, no creation.
    verify(files, never()).create(any(), any());
    verify(files, never()).list();
  }

  @Test
  void fallsBackToNameLookupForTheApplicationFolderWhenNotConfigured()
      throws IOException {
    File folder = new File();
    folder.setId("named-app-folder");
    FileList result = new FileList();
    result.setFiles(List.of(folder));
    when(list.execute()).thenReturn(result);

    GoogleDriveService service = new GoogleDriveService(drive, "", "");

    assertThat(service.findOrCreateFolder()).isEqualTo("named-app-folder");
  }

  /**
   * A blank {@code platform-folder-id} must resolve by name — it must never fall
   * back to the application folder, which is the whole point of the separation.
   */
  @Test
  void resolvesPlatformFolderByNameWhenNoPlatformIdIsConfigured() throws IOException {
    driveContainsFolder(DISCOVERED_PLATFORM_FOLDER_ID);
    GoogleDriveService service =
        new GoogleDriveService(drive, CONFIGURED_APPLICATION_FOLDER_ID, "   ");

    assertThat(service.findOrCreatePlatformFolder())
        .isEqualTo(DISCOVERED_PLATFORM_FOLDER_ID)
        .isNotEqualTo(CONFIGURED_APPLICATION_FOLDER_ID);
  }

  @Test
  void honoursAnExplicitlyConfiguredPlatformFolderId() throws IOException {
    GoogleDriveService service =
        new GoogleDriveService(drive, CONFIGURED_APPLICATION_FOLDER_ID, "pinned-platform");

    assertThat(service.findOrCreatePlatformFolder()).isEqualTo("pinned-platform");
    verify(files, never()).list();
  }

  @Test
  void createsThePlatformFolderOnFirstUse() throws IOException {
    driveContainsNoFolders();

    File created = new File();
    created.setId("created-platform-folder");
    Drive.Files.Create create = mock(Drive.Files.Create.class);
    when(files.create(any(File.class))).thenReturn(create);
    when(create.setFields(anyString())).thenReturn(create);
    when(create.setSupportsAllDrives(anyBoolean())).thenReturn(create);
    when(create.execute()).thenReturn(created);

    GoogleDriveService service = new GoogleDriveService(drive, "", "");

    assertThat(service.findOrCreatePlatformFolder()).isEqualTo("created-platform-folder");
  }

  @Test
  void looksUpThePlatformFolderByItsOwnName() throws IOException {
    driveContainsFolder(DISCOVERED_PLATFORM_FOLDER_ID);
    GoogleDriveService service = new GoogleDriveService(drive, "", "");

    service.findOrCreatePlatformFolder();

    verify(list).setQ(org.mockito.ArgumentMatchers.contains(
        GoogleDriveService.PLATFORM_FOLDER_NAME));
  }

  @Test
  void usesDistinctNamesForTheTwoFolders() {
    assertThat(GoogleDriveService.PLATFORM_FOLDER_NAME)
        .isNotEqualTo(GoogleDriveService.FOLDER_NAME)
        .isEqualTo("simonrowe-platform-backups");
  }
}
