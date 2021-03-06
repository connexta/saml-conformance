/*
Copyright (c) 2018 Codice Foundation

Released under the GNU Lesser General Public License; see
http://www.gnu.org/licenses/lgpl.html
*/
package org.codice.security.saml;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.opensaml.saml.saml2.metadata.Endpoint;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.security.credential.UsageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IdpMetadata {

  private static final Logger LOGGER = LoggerFactory.getLogger(IdpMetadata.class);

  private static final String SAML_2_0_PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";

  private static final String BINDINGS_HTTP_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

  private static final String BINDINGS_HTTP_REDIRECT =
      "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";

  private String singleSignOnLocation;

  private String singleSignOnBinding;

  private String signingCertificate;

  private String encryptionCertificate;

  private String metadata;

  protected AtomicReference<EntityData> entityData = new AtomicReference<>();

  private String singleLogoutBinding;

  private String singleLogoutLocation;

  public void setMetadata(String metadata) {
    this.metadata = metadata;
    entityData.getAndSet(null);
  }

  private void initSingleSignOn() {
    IDPSSODescriptor descriptor = getDescriptor();
    if (descriptor != null) {
      // Prefer HTTP-Redirect over HTTP-POST if both are present
      Optional<? extends Endpoint> service =
          initSingleSomething(descriptor.getSingleSignOnServices());

      if (service.isPresent()) {
        singleSignOnBinding = service.get().getBinding();
        singleSignOnLocation = service.get().getLocation();
      }
    }
  }

  private void initSingleSignOut() {
    IDPSSODescriptor descriptor = getDescriptor();
    if (descriptor != null) {

      // Prefer HTTP-Redirect over HTTP-POST if both are present
      Optional<? extends Endpoint> service =
          initSingleSomething(descriptor.getSingleLogoutServices());

      if (service.isPresent()) {
        singleLogoutBinding = service.get().getBinding();
        singleLogoutLocation = service.get().getLocation();
      }
    }
  }

  private Optional<? extends Endpoint> initSingleSomething(List<? extends Endpoint> endpoints) {
    IDPSSODescriptor descriptor = getDescriptor();
    if (descriptor == null) {
      return Optional.empty();
    }

    // Prefer HTTP-Redirect over HTTP-POST if both are present
    return endpoints
        .stream()
        .filter(Objects::nonNull)
        .filter(s -> Objects.nonNull(s.getBinding()))
        .filter(
            s ->
                s.getBinding().equals(BINDINGS_HTTP_POST)
                    || s.getBinding().equals(BINDINGS_HTTP_REDIRECT))
        .reduce(
            (acc, val) -> {
              if (!BINDINGS_HTTP_REDIRECT.equals(acc.getBinding())) {
                return val;
              }
              return acc;
            });
  }

  private void initCertificates() {
    IDPSSODescriptor descriptor = getDescriptor();
    if (descriptor == null) {
      return;
    }

    for (KeyDescriptor key : descriptor.getKeyDescriptors()) {
      String certificate = null;
      if (!key.getKeyInfo().getX509Datas().isEmpty()
          && !key.getKeyInfo().getX509Datas().get(0).getX509Certificates().isEmpty()) {
        certificate =
            key.getKeyInfo().getX509Datas().get(0).getX509Certificates().get(0).getValue();
      }

      if (StringUtils.isBlank(certificate)) {
        break;
      }

      if (UsageType.UNSPECIFIED.equals(key.getUse())) {
        encryptionCertificate = certificate;
        signingCertificate = certificate;
      }

      if (UsageType.ENCRYPTION.equals(key.getUse())) {
        encryptionCertificate = certificate;
      }

      if (UsageType.SIGNING.equals(key.getUse())) {
        signingCertificate = certificate;
      }
    }
  }

  /**
   * If the metadata is past its validity date, the SAML standard prohibits using the metadata. The
   * SAML entity data is cleared if the metadata is invalid. This forces the class to attempt to
   * retrieve the SAML entity's metadata from its source. If the metadata is expired, it can
   * continue to be used, but the class attempts to get a new copy of it from the source. If fresh
   * metadata is successfully retrieved, the cached entity data is cleared.
   *
   * @return The root SAML entity descriptor or null
   */
  @Nullable
  EntityDescriptor getEntityDescriptor() {

    EntityData previousEntityData = null;

    if (!isMetadataValid()) {
      LOGGER.debug("SSO metadata is invalid. Purging metadata cache");
      // Do not permit existing metadata to be used again.
      entityData.set(null);
    }

    if (isMetadataExpired()) {
      LOGGER.debug("SSO metadata cache is expired. Attempt to retrieve new metadata");
      // Cache existing metadata
      previousEntityData = entityData.getAndSet(null);
    }

    // Attempt to get new metadata. If that is not possible, attempt to fallback to existing
    // metadata.
    EntityData newEntityData = null;
    if (entityData.get() == null) {
      newEntityData =
          Optional.ofNullable(parseMetadata())
              .map(this::extractRootEntityFromMap)
              .map(EntityData::new)
              .orElse(previousEntityData);
    }

    boolean updated = entityData.compareAndSet(null, newEntityData);
    if (!updated) {
      LOGGER.trace("Safe but concurrent update to SAML entity; using processed value");
    }

    final EntityData ed = entityData.get();
    return ed == null ? null : ed.getEntityDescriptor();
  }

  protected boolean isMetadataExpired() {

    EntityData ed = this.entityData.get();
    if (ed == null) {
      return false;
    }
    return ed.isMetadataExpired();
  }

  protected boolean isMetadataValid() {

    EntityData ed = this.entityData.get();
    if (ed == null) {
      return true;
    }

    return ed.isMetadataValid();
  }

  @Nullable
  public Map<String, EntityDescriptor> parseMetadata() {
    final Map<String, EntityDescriptor> processMap = new ConcurrentHashMap<>();
    MetadataConfigurationParser metadataConfigurationParser;
    metadataConfigurationParser =
        new MetadataConfigurationParser(metadata, ed -> processMap.put(ed.getEntityID(), ed));
    processMap.putAll(metadataConfigurationParser.getEntryDescriptions());

    return processMap;
  }

  public IDPSSODescriptor getDescriptor() {
    EntityDescriptor entityDescriptor = getEntityDescriptor();
    if (entityDescriptor != null) {
      return entityDescriptor.getIDPSSODescriptor(SAML_2_0_PROTOCOL);
    }
    return null;
  }

  public String getSingleSignOnLocation() {
    initSingleSignOn();
    return singleSignOnLocation;
  }

  public String getSingleSignOnBinding() {
    initSingleSignOn();
    return singleSignOnBinding;
  }

  public String getSingleLogoutBinding() {
    initSingleSignOut();
    return singleLogoutBinding;
  }

  public String getSingleLogoutLocation() {
    initSingleSignOut();
    return singleLogoutLocation;
  }

  public String getEntityId() {
    EntityDescriptor entityDescriptor = getEntityDescriptor();
    if (entityDescriptor != null) {
      return entityDescriptor.getEntityID();
    }
    return null;
  }

  public String getSigningCertificate() {
    initCertificates();
    return signingCertificate;
  }

  @SuppressWarnings("unused")
  public String getEncryptionCertificate() {
    initCertificates();
    return encryptionCertificate;
  }

  @Nullable
  private EntityDescriptor extractRootEntityFromMap(Map<String, EntityDescriptor> edMap) {
    Set<Map.Entry<String, EntityDescriptor>> entries = edMap.entrySet();
    if (!entries.isEmpty()) {
      return entries.iterator().next().getValue();
    }
    return null;
  }

  static class EntityData {

    private final EntityDescriptor entityDescriptor;
    private final Duration cacheDuration;
    private final Instant validUntil;
    private final Instant created;

    EntityData(EntityDescriptor ed) {
      entityDescriptor = ed;
      if (getEntityDescriptor() == null) {
        cacheDuration = null;
        validUntil = null;
        created = null;
      } else {
        created = Instant.now();
        Long entityDuration = getEntityDescriptor().getCacheDuration();
        DateTime entityValidity = getEntityDescriptor().getValidUntil();
        this.cacheDuration = (entityDuration != null) ? Duration.ofMillis(entityDuration) : null;
        this.validUntil = (entityValidity != null) ? entityValidity.toDate().toInstant() : null;
      }
    }

    @Nullable
    public EntityDescriptor getEntityDescriptor() {
      return entityDescriptor;
    }

    /**
     * Return true if this the cache metadata is expired and should be retrieved from the SAML
     * entity that provides the metadata. From the SAML standard: "Note that cache expiration does
     * not imply a lack of validity in the absence of a validUntil attribute or other information;
     * failure to update a cached instance (e.g., due to network failure) need not render metadata
     * invalid..." Because cacheDuration is optional (per the standard), the absence of a cache
     * duration implies indefinite expiration.
     *
     * @return true if metadata should be reacquired from the IDP based solely on cacheDuration
     */
    private boolean isMetadataExpired() {

      // Cache cannot be expired if there is no duration.
      if (getCacheDuration() == null) {
        return false;
      }

      // Logically, a duration of 0 means the cache is always expired
      if (getCacheDuration().isZero()) {
        return true;
      }

      return Instant.now().isAfter(created.plus(getCacheDuration()));
    }

    /**
     * Return true if the metadata may still be used. Invalid data must NOT be used, per the SAML
     * standard. The SAML standard does not give explicit instruction on what to do if the
     * validUntil is not specified, but implies The attribute is optional. Therefore the absence of
     * valdiUntil means indefinite validity. From the SAML standard: "validUntil ... [is an]
     * optional attribute [that] indicates the expiration time of the metadata contained in the
     * element and any contained elements." However, the standard also sates "When used as the root
     * element of a metadata instance, this element MUST contain either a validUntil or
     * cacheDuration attribute."
     *
     * @return true if metadata may still be used
     */
    private boolean isMetadataValid() {

      // If validUntil is not specified, then the cache duration must be specified (per the
      // standard)
      if (validUntil == null) {
        return getCacheDuration() != null;
      }

      return Instant.now().isBefore(validUntil);
    }

    public Duration getCacheDuration() {
      return cacheDuration;
    }
  }
}
