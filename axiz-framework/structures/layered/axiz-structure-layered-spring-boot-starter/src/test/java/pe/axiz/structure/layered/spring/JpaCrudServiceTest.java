package pe.axiz.structure.layered.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.commons.PageRequest;
import pe.axiz.core.commons.Sort;

class JpaCrudServiceTest {

  @Test
  void pagingIsTranslatedToSpringData() {
    org.springframework.data.domain.PageRequest p =
        JpaCrudService.toPageable(new PageRequest(2, 25, Sort.parse(List.of("name,desc", "code"))));
    assertEquals(2, p.getPageNumber());
    assertEquals(25, p.getPageSize());
    assertEquals(
        org.springframework.data.domain.Sort.Direction.DESC,
        p.getSort().getOrderFor("name").getDirection());
    assertEquals(
        org.springframework.data.domain.Sort.Direction.ASC,
        p.getSort().getOrderFor("code").getDirection());
  }
}
