package model.router.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import model.router.service.ModelCatalog;

/**
 * /v1/models —— 全站唯一的模型清单出口（OpenAI 兼容格式）。
 *
 * <p>清单本身由 {@link ModelCatalog} 统一计算，控制台的 /ui/api/status 用的是同一份，
 * 避免「入口的清单」和「看板的清单」各说各话。
 */
@RestController
@RequestMapping("/v1")
public class ModelsController {

  private final ModelCatalog catalog;

  public ModelsController(ModelCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/models")
  public Map<String, Object> models() {
    List<Map<String, Object>> data = catalog.entries().stream().map(item -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", item.id());
      row.put("object", "model");
      row.put("created", 0);
      row.put("owned_by", item.owners().isEmpty() ? "router" : item.owners().get(0));
      row.put("kind", item.kind());
      row.put("owners", item.owners());
      row.put("available", item.available());
      return row;
    }).toList();
    return Map.of("object", "list", "data", data);
  }
}
