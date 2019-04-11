package io.onedev.server.web.page.project.blob.render.renderers.source;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnDomReadyHeaderItem;
import org.apache.wicket.markup.head.OnLoadHeaderItem;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.form.FormComponentPanel;
import org.unbescape.javascript.JavaScriptEscape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.onedev.server.OneDev;
import io.onedev.server.model.support.TextRange;
import io.onedev.server.web.component.sourceformat.OptionChangeCallback;
import io.onedev.server.web.component.sourceformat.SourceFormatPanel;
import io.onedev.server.web.page.project.blob.render.BlobRenderContext;
import io.onedev.server.web.page.project.blob.render.BlobRenderContext.Mode;
import io.onedev.server.web.page.project.blob.render.edit.BlobEditPanel;
import io.onedev.server.web.page.project.blob.render.view.Markable;

@SuppressWarnings("serial")
public class SourceEditPanel extends BlobEditPanel implements Markable {

	private SourceFormatPanel sourceFormat;
	
	public SourceEditPanel(String id, BlobRenderContext context) {
		super(id, context);
	}

	@Override
	protected FormComponentPanel<byte[]> newEditor(String componentId, byte[] initialContent) {
		return new SourceFormComponent(componentId, initialContent);
	}

	@Override
	protected WebMarkupContainer newEditOptions(String componentId) {
		sourceFormat = new SourceFormatPanel(componentId, new OptionChangeCallback() {

			@Override
			public void onOptioneChange(AjaxRequestTarget target) {
				String script = String.format("onedev.server.sourceEdit.onIndentTypeChange('%s', '%s');", 
						getEditor().getMarkupId(), sourceFormat.getIndentType());
				target.appendJavaScript(script);
			}
			
		}, new OptionChangeCallback() {

			@Override
			public void onOptioneChange(AjaxRequestTarget target) {
				String script = String.format("onedev.server.sourceEdit.onTabSizeChange('%s', %s);", 
						getEditor().getMarkupId(), sourceFormat.getTabSize());
				target.appendJavaScript(script);
			}
			
		}, new OptionChangeCallback() {
			
			@Override
			public void onOptioneChange(AjaxRequestTarget target) {
				String script = String.format("onedev.server.sourceEdit.onLineWrapModeChange('%s', '%s');", 
						getEditor().getMarkupId(), sourceFormat.getLineWrapMode());
				target.appendJavaScript(script);
			}
			
		});	
		return sourceFormat;
	}

	@Override
	public void renderHead(IHeaderResponse response) {
		super.renderHead(response);
		response.render(JavaScriptHeaderItem.forReference(new SourceEditResourceReference()));

		String autosaveKey = JavaScriptEscape.escapeJavaScript(context.getAutosaveKey());
		String jsonOfMark = context.getMark()!=null?getJson(context.getMark()):"undefined"; 
		String script = String.format("onedev.server.sourceEdit.onDomReady('%s', '%s', %s, '%s', %s, '%s', %b, '%s');", 
				getEditor().getMarkupId(), 
				JavaScriptEscape.escapeJavaScript(context.getNewPath()), 
				jsonOfMark,
				sourceFormat.getIndentType(), 
				sourceFormat.getTabSize(), 
				sourceFormat.getLineWrapMode(), 
				context.getMode() == Mode.EDIT || context.getInitialNewPath() != null, 
				autosaveKey);
		response.render(OnDomReadyHeaderItem.forScript(script));
		
		script = String.format("onedev.server.sourceEdit.onWindowLoad('%s', %s, '%s');", 
				getEditor().getMarkupId(), jsonOfMark, autosaveKey);
		response.render(OnLoadHeaderItem.forScript(script));
	}

	private String getJson(TextRange mark) {
		try {
			return OneDev.getInstance(ObjectMapper.class).writeValueAsString(mark);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void mark(AjaxRequestTarget target, TextRange mark) {
		String script;
		if (mark != null) {
			script = String.format("onedev.server.sourceEdit.mark('%s', %s);", 
					getEditor().getMarkupId(), getJson(mark));
		} else {
			script = String.format("onedev.server.sourceEdit.mark('%s', undefined);", 
					getEditor().getMarkupId());
		}
		target.appendJavaScript(script);
	}

}
