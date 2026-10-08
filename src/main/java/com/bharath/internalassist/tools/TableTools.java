package com.bharath.internalassist.tools;

import com.bharath.internalassist.tabular.TableData;
import com.bharath.internalassist.tabular.TableRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class TableTools {

    private final TableRegistry registry;

    @Tool(description = " List every available data table with its columns and row count. "
    + "Call this when you are not sure which table or column to use.",
            name = "listTablesTool"
    )
    public String listTables(){
        log.info("TOOL listTables");
        return registry.dictionary();
    }

    @Tool(description = "Find rows in a data table where one column exactly equals a value. "
    + "Use it when the question names one specific item such as a person, an event or an ID. "
            + "Leave column and value empty to see the first rows of a table. Returns at most 20 rows.",
            name = "lookupRowsTool"
    )
    public String lookupRows(
            @ToolParam(description = "Table id exactly as listed, for example team-events.TeamEvents") String table,
            @ToolParam(description = "Column to filter on, for example EventName", required = false) String column,
            @ToolParam(description = "The exact value to match, for example Diwali Quiz 2025", required = false) String value
    ){
        log.info("TOOL lookupRows table={} column={} value={}", table, column, value);
        var found = registry.find(table == null ? "" : table);
        if(found.isEmpty()){
            return "Unknown table "+ table+ ". Available tables:\n"+ registry.dictionary();
        }
        TableData data = found.get();
        try{
            List<Map<String, String>> rows = data.where(column, value,20);
            if(!rows.isEmpty()){
                return rows.size()+ " rows(s) from"+ data.id()+":\n" + format(rows);
            }
            StringBuilder reply = new StringBuilder("No rows in "+ data.id()+" where "+ column + " = "+ value+ ". Values must match exactly.");
            List<Map<String, String>> close = data.containing(column, value, 5);
            if(!close.isEmpty()){
                reply.append("\n Close matches ( the call contains your text):\n").append(format(close));
            }
            return reply.toString();
        }
        catch (IllegalArgumentException e){
            return e.getMessage();
        }
    }

    private static String format(List<Map<String ,String>> rows){
        return rows.stream()
                .map(row->"- "+ row.entrySet().stream()
                        .filter(e-> !e.getValue().isBlank())
                        .map(e-> e.getKey()+" = "+ e.getValue())
                        .collect(Collectors.joining("; "))
                ).collect(Collectors.joining("\n"));
    }


}
