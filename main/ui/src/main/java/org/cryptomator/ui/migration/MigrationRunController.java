package org.cryptomator.ui.migration;

import dagger.Lazy;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.stage.Stage;
import org.cryptomator.common.vaults.Vault;
import org.cryptomator.common.vaults.VaultState;
import org.cryptomator.cryptofs.common.FileSystemCapabilityChecker;
import org.cryptomator.cryptofs.migration.Migrators;
import org.cryptomator.cryptofs.migration.api.MigrationProgressListener;
import org.cryptomator.cryptolib.api.InvalidPassphraseException;
import org.cryptomator.keychain.KeychainAccess;
import org.cryptomator.keychain.KeychainAccessException;
import org.cryptomator.ui.common.Animations;
import org.cryptomator.ui.common.ErrorComponent;
import org.cryptomator.ui.common.FxController;
import org.cryptomator.ui.common.FxmlFile;
import org.cryptomator.ui.common.FxmlScene;
import org.cryptomator.ui.common.Tasks;
import org.cryptomator.ui.controls.NiceSecurePasswordField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.cryptomator.common.Constants.MASTERKEY_FILENAME;

@MigrationScoped
public class MigrationRunController implements FxController {

	private static final Logger LOG = LoggerFactory.getLogger(MigrationRunController.class);
	private static final long MIGRATION_PROGRESS_UPDATE_MILLIS = 50;

	private final Stage window;
	private final Vault vault;
	private final ExecutorService executor;
	private final ScheduledExecutorService scheduler;
	private final Optional<KeychainAccess> keychainAccess;
	private final ObjectProperty<FileSystemCapabilityChecker.Capability> missingCapability;
	private final ErrorComponent.Builder errorComponent;
	private final Lazy<Scene> startScene;
	private final Lazy<Scene> successScene;
	private final ObjectBinding<ContentDisplay> migrateButtonContentDisplay;
	private final Lazy<Scene> capabilityErrorScene;
	private final BooleanProperty migrationButtonDisabled;
	private final DoubleProperty migrationProgress;
	private volatile double volatileMigrationProgress = -1.0;
	public NiceSecurePasswordField passwordField;

	@Inject
	public MigrationRunController(@MigrationWindow Stage window, @MigrationWindow Vault vault, ExecutorService executor, ScheduledExecutorService scheduler, Optional<KeychainAccess> keychainAccess, @Named("capabilityErrorCause") ObjectProperty<FileSystemCapabilityChecker.Capability> missingCapability, @FxmlScene(FxmlFile.MIGRATION_START) Lazy<Scene> startScene, @FxmlScene(FxmlFile.MIGRATION_SUCCESS) Lazy<Scene> successScene, @FxmlScene(FxmlFile.MIGRATION_CAPABILITY_ERROR) Lazy<Scene> capabilityErrorScene, ErrorComponent.Builder errorComponent) {
		this.window = window;
		this.vault = vault;
		this.executor = executor;
		this.scheduler = scheduler;
		this.keychainAccess = keychainAccess;
		this.missingCapability = missingCapability;
		this.errorComponent = errorComponent;
		this.startScene = startScene;
		this.successScene = successScene;
		this.migrateButtonContentDisplay = Bindings.createObjectBinding(this::getMigrateButtonContentDisplay, vault.stateProperty());
		this.capabilityErrorScene = capabilityErrorScene;
		this.migrationButtonDisabled = new SimpleBooleanProperty();
		this.migrationProgress = new SimpleDoubleProperty(volatileMigrationProgress);
	}

	public void initialize() {
		if (keychainAccess.isPresent()) {
			loadStoredPassword();
		}
		migrationButtonDisabled.bind(vault.stateProperty().isNotEqualTo(VaultState.NEEDS_MIGRATION).or(passwordField.textProperty().isEmpty()));
	}

	@FXML
	public void back() {
		window.setScene(startScene.get());
	}

	@FXML
	public void migrate() {
		LOG.info("Migrating vault {}", vault.getPath());
		CharSequence password = passwordField.getCharacters();
		vault.setState(VaultState.PROCESSING);
		ScheduledFuture<?> progressSyncTask = scheduler.scheduleAtFixedRate(() -> {
			Platform.runLater(() -> {
				migrationProgress.set(volatileMigrationProgress);
			});
		}, 0, MIGRATION_PROGRESS_UPDATE_MILLIS, TimeUnit.MILLISECONDS);
		Tasks.create(() -> {
			Migrators migrators = Migrators.get();
			migrators.migrate(vault.getPath(), MASTERKEY_FILENAME, password, this::migrationProgressChanged);
			return migrators.needsMigration(vault.getPath(), MASTERKEY_FILENAME);
		}).onSuccess(needsAnotherMigration -> {
			if (needsAnotherMigration) {
				LOG.info("Migration of '{}' succeeded, but another migration is required.", vault.getDisplayableName());
				vault.setState(VaultState.NEEDS_MIGRATION);
			} else {
				LOG.info("Migration of '{}' succeeded.", vault.getDisplayableName());
				vault.setState(VaultState.LOCKED);
				passwordField.swipe();
				window.setScene(successScene.get());
			}
		}).onError(InvalidPassphraseException.class, e -> {
			Animations.createShakeWindowAnimation(window).play();
			passwordField.selectAll();
			passwordField.requestFocus();
			vault.setState(VaultState.NEEDS_MIGRATION);
		}).onError(FileSystemCapabilityChecker.MissingCapabilityException.class, e -> {
			LOG.error("Underlying file system not supported.", e);
			vault.setState(VaultState.ERROR);
			missingCapability.set(e.getMissingCapability());
			window.setScene(capabilityErrorScene.get());
		}).onError(Exception.class, e -> { // including RuntimeExceptions
			LOG.error("Migration failed for technical reasons.", e);
			vault.setState(VaultState.NEEDS_MIGRATION);
			errorComponent.cause(e).window(window).returnToScene(startScene.get()).build().showErrorScene();
		}).andFinally(() -> {
			progressSyncTask.cancel(true);
		}).runOnce(executor);
	}

	// Called by a background task. We can not directly modify observable properties from here
	private void migrationProgressChanged(MigrationProgressListener.ProgressState state, double progress) {
		switch (state) {
			case INITIALIZING:
				volatileMigrationProgress = -1.0;
				break;
			case MIGRATING:
				volatileMigrationProgress = progress;
				break;
			case FINALIZING:
				volatileMigrationProgress = 1.0;
				break;
			default:
				throw new IllegalStateException("Unexpted state " + state);
		}
	}

	private void loadStoredPassword() {
		assert keychainAccess.isPresent();
		char[] storedPw = null;
		try {
			storedPw = keychainAccess.get().loadPassphrase(vault.getId());
			if (storedPw != null) {
				passwordField.setPassword(storedPw);
				passwordField.selectRange(storedPw.length, storedPw.length);
			}
		} catch (KeychainAccessException e) {
			LOG.error("Failed to load entry from system keychain.", e);
		} finally {
			if (storedPw != null) {
				Arrays.fill(storedPw, ' ');
			}
		}
	}

	/* Getter/Setter */

	public Vault getVault() {
		return vault;
	}

	public BooleanProperty migrationButtonDisabledProperty() {
		return migrationButtonDisabled;
	}

	public boolean isMigrationButtonDisabled() {
		return migrationButtonDisabled.get();
	}

	public ObjectBinding<ContentDisplay> migrateButtonContentDisplayProperty() {
		return migrateButtonContentDisplay;
	}

	public ContentDisplay getMigrateButtonContentDisplay() {
		switch (vault.getState()) {
			case PROCESSING:
				return ContentDisplay.LEFT;
			default:
				return ContentDisplay.TEXT_ONLY;
		}
	}

	public ReadOnlyDoubleProperty migrationProgressProperty() {
		return migrationProgress;
	}

	public double getMigrationProgress() {
		return migrationProgress.get();
	}

}
