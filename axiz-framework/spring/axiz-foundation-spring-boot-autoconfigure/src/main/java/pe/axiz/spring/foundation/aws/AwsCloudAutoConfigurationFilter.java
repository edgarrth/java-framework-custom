package pe.axiz.spring.foundation.aws;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.util.ClassUtils;

/** Prevent generic provider autowiring in Cloud AWS; its clients use the managed configurer. */
public final class AwsCloudAutoConfigurationFilter
    implements AutoConfigurationImportFilter, BeanClassLoaderAware {
  private ClassLoader loader = AwsCloudAutoConfigurationFilter.class.getClassLoader();

  @Override
  public void setBeanClassLoader(ClassLoader loader) {
    this.loader = loader;
  }

  @Override
  public boolean[] match(@Nullable String[] names, AutoConfigurationMetadata metadata) {
    boolean managed =
        ClassUtils.isPresent("pe.axiz.adapter.aws.identity.AwsCredentialSource", loader);
    boolean[] result = new boolean[names.length];
    for (int i = 0; i < names.length; i++)
      result[i] =
          !managed
              || !("io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration"
                      .equals(names[i])
                  || "io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration".equals(names[i]));
    return result;
  }
}
